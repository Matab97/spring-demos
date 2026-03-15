# DOKS GPU Inference Deployment
Guide for deploying fine-tuned LLM inference models (Model A, B, C) onto the existing DigitalOcean Kubernetes (DOKS) cluster alongside current backend services.
---
## Overview
Three fine-tuned models are served as internal Kubernetes microservices on a dedicated GPU node pool added to the existing cluster. Backend services call them over the internal cluster network — no internet egress, no external API keys.
| Model | Task | Base model | VRAM |
|-------|------|-----------|------|
| Model A | Description generation | Qwen2.5-1.5B | ~4 GB |
| Model B | Map enrichment + quality scoring | Phi-4-mini (3.8B) | ~8 GB |
| Model C | Restep sequence generation | Qwen2.5-3B | ~7 GB |
All three fit on a single L40S (48 GB) to start. Split to dedicated nodes as load grows.
---
## Architecture
```
DOKS Cluster (existing)
├── CPU node pool
│   └── existing backend services (unchanged)
│
└── GPU node pool (L40S or RTX 6000 Ada)
    ├── vllm-model-a  (Qwen2.5-1.5B)
    ├── vllm-model-b  (Phi-4-mini)
    └── vllm-model-c  (Qwen2.5-3B)
DigitalOcean Managed NFS (VPC-private, ReadWriteMany)
└── /models/
    ├── model-a/
    ├── model-b/
    └── model-c/
Internal ClusterIP services
├── model-a.inference.svc.cluster.local:8000
├── model-b.inference.svc.cluster.local:8000
└── model-c.inference.svc.cluster.local:8000
```
---
## Prerequisites
- Existing DOKS cluster in **NYC2** or **ATL1** (both support H100 GPUs + Managed NFS)
- Trained LoRA adapter weights stored in DigitalOcean Spaces
- `kubectl` configured and pointing at the cluster
> DOKS automatically installs the NVIDIA Device Plugin, GPU drivers, and DCGM Exporter when a GPU node pool is added. No manual driver setup required.
---
## Step 1 — Add a GPU node pool
In the DigitalOcean Control Panel:
1. Navigate to **Kubernetes → your cluster → Node Pools**
2. Click **Add Node Pool**
3. Select droplet type: `gpu-l40s-1x48gb` (L40S, 48 GB VRAM) — fits all three models
4. Set initial count to **1**; scale-to-zero will handle idle hours
5. Click **Save**
Verify GPU nodes are ready and advertising the resource:
```bash
kubectl get nodes -l doks.digitalocean.com/gpu-brand=nvidia
kubectl get nodes -o custom-columns="NAME:.metadata.name,GPU:.status.allocatable.nvidia\.com/gpu"
```
---
## Step 2 — Create Managed NFS storage
1. In the Control Panel go to **Storage → Volumes → NFS**
2. Create a new NFS share in the **same VPC** as your cluster
3. Set size to at least **50 GB** (weights for all three models)
4. Note the NFS mount path (format: `nfs-server-ip:/export/path`)
Create a PersistentVolume and PersistentVolumeClaim pointing at the NFS share:
```yaml
# nfs-pv.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: models-nfs-pv
spec:
  capacity:
    storage: 50Gi
  accessModes:
    - ReadWriteMany        # critical — allows multiple pods to mount simultaneously
  nfs:
    server: <NFS_SERVER_IP>
    path: /export/models
  persistentVolumeReclaimPolicy: Retain
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: models-nfs-pvc
  namespace: inference
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 50Gi
  volumeName: models-nfs-pv
```
```bash
kubectl create namespace inference
kubectl apply -f nfs-pv.yaml
```
### Download model weights to NFS (one-time)
Spin up a temporary pod that mounts the NFS and pulls weights from DigitalOcean Spaces:
```bash
kubectl run model-downloader \
  --image=python:3.11-slim \
  --restart=Never \
  --namespace=inference \
  --overrides='{
    "spec": {
      "volumes": [{"name":"models","persistentVolumeClaim":{"claimName":"models-nfs-pvc"}}],
      "containers": [{
        "name":"downloader",
        "image":"python:3.11-slim",
        "command":["sleep","3600"],
        "volumeMounts":[{"name":"models","mountPath":"/models"}]
      }]
    }
  }'
# Shell into the pod and pull weights
kubectl exec -it model-downloader -n inference -- bash
# Inside the pod — example using huggingface-hub or your Spaces bucket
pip install huggingface_hub
python -c "
from huggingface_hub import snapshot_download
snapshot_download('your-org/model-a-adapter', local_dir='/models/model-a')
snapshot_download('your-org/model-b-adapter', local_dir='/models/model-b')
snapshot_download('your-org/model-c-adapter', local_dir='/models/model-c')
"
# Clean up when done
kubectl delete pod model-downloader -n inference
```
---
## Step 3 — Deploy vLLM per model
Each model gets its own Deployment and ClusterIP Service. The pattern is identical across all three — only the model name, adapter path, and resource requests differ.
### Model A — description generator
```yaml
# vllm-model-a.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm-model-a
  namespace: inference
spec:
  replicas: 1
  selector:
    matchLabels:
      app: vllm-model-a
  template:
    metadata:
      labels:
        app: vllm-model-a
    spec:
      # Schedule only on GPU nodes
      nodeSelector:
        doks.digitalocean.com/gpu-brand: nvidia
      tolerations:
        - key: nvidia.com/gpu
          operator: Exists
          effect: NoSchedule
      volumes:
        - name: models
          persistentVolumeClaim:
            claimName: models-nfs-pvc
        - name: shm
          emptyDir:
            medium: Memory
            sizeLimit: 8Gi
      containers:
        - name: vllm
          image: vllm/vllm-openai:latest
          args:
            - --model=/models/model-a
            - --served-model-name=model-a
            - --gpu-memory-utilization=0.3    # ~30% of 48 GB for a 1.5B model
            - --max-model-len=4096
            - --port=8000
          ports:
            - containerPort: 8000
          resources:
            requests:
              nvidia.com/gpu: 1
              memory: "8Gi"
              cpu: "2"
            limits:
              nvidia.com/gpu: 1
              memory: "16Gi"
              cpu: "4"
          volumeMounts:
            - name: models
              mountPath: /models
            - name: shm
              mountPath: /dev/shm
          readinessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 60
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 120
            periodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: model-a
  namespace: inference
spec:
  selector:
    app: vllm-model-a
  ports:
    - protocol: TCP
      port: 8000
      targetPort: 8000
  type: ClusterIP
```
### Model B — map enrichment + quality scoring
```yaml
# vllm-model-b.yaml
# Same structure as model-a — change these fields:
#   name: vllm-model-b
#   app: vllm-model-b
#   --model=/models/model-b
#   --served-model-name=model-b
#   --gpu-memory-utilization=0.35   # Phi-4-mini is 3.8B
```
### Model C — restep generator
```yaml
# vllm-model-c.yaml
# Same structure — change these fields:
#   name: vllm-model-c
#   app: vllm-model-c
#   --model=/models/model-c
#   --served-model-name=model-c
#   --gpu-memory-utilization=0.30   # Qwen2.5-3B
#   Add: --guided-decoding-backend=outlines  # enables JSONformer-style constrained decoding
```
Apply all three:
```bash
kubectl apply -f vllm-model-a.yaml
kubectl apply -f vllm-model-b.yaml
kubectl apply -f vllm-model-c.yaml
# Watch pods come up (model load takes 30–90 seconds from NFS)
kubectl get pods -n inference -w
```
---
## Step 4 — Calling models from your backend
All three models expose an OpenAI-compatible API. From any service in the cluster:
```typescript
// Model A — generate a technicalDescription for a step
const res = await fetch('http://model-a.inference.svc.cluster.local:8000/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    model: 'model-a',
    messages: [
      { role: 'system', content: 'Generate a neutral one-sentence technicalDescription for the given Webflow step.' },
      { role: 'user', content: JSON.stringify({ action: 'Add', selector: 'Container', target: 'body' }) }
    ],
    max_tokens: 64,
    temperature: 0.0
  })
});
// Model C — generate a restep from a goal description
const res = await fetch('http://model-c.inference.svc.cluster.local:8000/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    model: 'model-c',
    messages: [
      { role: 'system', content: 'Generate a Webflow Restep JSON array for the described goal. Output valid JSON only.' },
      { role: 'user', content: 'Create a hero section with a full-viewport height, flex layout, and centered content.' }
    ],
    max_tokens: 1024,
    temperature: 0.1
  })
});
```
---
## Step 5 — Autoscaling and cost control
### Horizontal Pod Autoscaler (scale replicas on load)
```yaml
# hpa-model-c.yaml — scale restep generator up to 3 replicas under load
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: hpa-model-c
  namespace: inference
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: vllm-model-c
  minReplicas: 1
  maxReplicas: 3
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300   # slow scale-down to avoid thrashing
```
### Node pool scale-to-zero
In the DOKS control panel, enable **Autoscale** on the GPU node pool with:
- Minimum nodes: **0**
- Maximum nodes: **3** (or as needed)
When no inference pods are scheduled, the GPU node scales down to zero and you pay nothing for idle time. New pods trigger a new node within ~2 minutes (model loads from NFS in <1 minute after the node is ready).
---
## Step 6 — Observability
DOKS installs DCGM Exporter automatically on GPU nodes, which exposes GPU utilization, memory, and temperature metrics to Prometheus.
Useful PromQL queries:
```promql
# GPU memory used per pod
DCGM_FI_DEV_FB_USED{namespace="inference"}
# GPU utilization per node
DCGM_FI_DEV_GPU_UTIL{namespace="inference"}
# vLLM request queue depth (requires vLLM metrics endpoint)
vllm:num_requests_waiting{namespace="inference"}
```
Add a Grafana dashboard with panels for:
- GPU memory used vs available (per model)
- Pending vs running requests
- Pod restarts (indicates OOM or startup failures)
- Node pool size over time (to audit scale-to-zero working)
---
## Updating model weights
When a new LoRA adapter is trained:
1. Upload new weights to DigitalOcean Spaces
2. Copy to NFS using the downloader pod pattern from Step 2
3. Rolling restart the affected deployment:
```bash
kubectl rollout restart deployment/vllm-model-a -n inference
kubectl rollout status deployment/vllm-model-a -n inference
```
No downtime — the old pod stays up until the new one passes its readiness probe.
---
## GPU sizing reference
| Node type | VRAM | Fits | Cost |
|-----------|------|------|------|
| RTX 4000 Ada | 20 GB | Model A or B individually | lowest |
| RTX 6000 Ada | 48 GB | All three models on one node | mid |
| L40S | 48 GB | All three models on one node | mid |
| H100 80 GB | 80 GB | All three + headroom for larger future models | highest |
Start with a single L40S or RTX 6000 Ada. Split models to dedicated nodes once per-model load justifies it.
---
## Troubleshooting
**Pod stuck in `Pending`**
```bash
kubectl describe pod <pod-name> -n inference
# Look for: "0/N nodes are available: insufficient nvidia.com/gpu"
# Fix: check GPU node pool has available nodes; check tolerations in the deployment spec
```
**Model loads but returns 500 errors**
```bash
kubectl logs deployment/vllm-model-a -n inference
# Common cause: --gpu-memory-utilization too high, OOM during model load
# Fix: reduce --gpu-memory-utilization or upgrade node type
```
**Slow cold start (>3 minutes)**
```bash
# NFS is the fastest storage option for multi-replica scenarios
# If still slow, check NFS throughput and proximity (use same region as cluster)
kubectl exec -it <pod> -n inference -- ls -lh /models/model-a
```
**Readiness probe failing**
```bash
# vLLM takes 60–120 seconds to load a model — increase initialDelaySeconds if needed
# Default in this config is 60s for readiness, 120s for liveness
```
