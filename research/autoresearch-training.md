# Training with autoresearch
How to use [karpathy/autoresearch](https://github.com/karpathy/autoresearch) to autonomously improve Model B (quality scorer) and Model C (restep generator) overnight, without manual intervention.
---
## What autoresearch is
autoresearch is a tight agent loop:
```
you write  →  program.md       (what to optimise, what to keep, how to decide)
agent edits →  train.py         (the single file it is allowed to touch)
loop runs  →  5-min experiment → measure metric → keep or revert → repeat
```
The agent works on a dedicated git branch, accumulating commits as it finds better configurations. Each experiment runs for exactly 5 minutes of wall-clock training time. The metric is `val_bpb` (validation bits per byte) — lower is better, and vocab-size-independent so architectural changes are fairly compared.
By design this means you can expect approximately 12 experiments per hour and approximately 100 experiments while you sleep.
For the Restep project, you adapt the loop in two ways:
1. **Model B** — replace `val_bpb` with your quality score as the metric. The agent tunes the scoring model's architecture and training hyperparameters to produce more accurate quality signals.
2. **Model C** — replace `val_bpb` with JSON validity rate + quality score on a held-out set of resteps. The agent finds the training configuration that produces the most structurally correct and highest-quality restep sequences.
---
## Prerequisites
```bash
# uv package manager (required)
curl -LsSf https://astral.sh/uv/install.sh | sh
# Clone autoresearch
git clone https://github.com/karpathy/autoresearch.git
cd autoresearch
# Install deps
uv sync
```
A single NVIDIA GPU is required. The L40S or RTX 6000 Ada on DOKS works well. For overnight runs, use a GPU Droplet directly (not a DOKS Job) so the process is not evicted.
---
## Repo structure
```
autoresearch/
├── prepare.py   ← fixed — data prep, tokeniser, dataloader, eval. DO NOT MODIFY.
├── train.py     ← the agent's only file. Architecture, optimiser, training loop.
└── program.md   ← your instructions to the agent. The only file you author.
```
The agent only touches `train.py`. This keeps the scope manageable and diffs reviewable. You control the agent entirely through `program.md` — you never need to read or write Python.
---
## Adapting autoresearch for Model B
Model B's training loop has two goals baked into one metric: how well the model scores restep quality. You adapt autoresearch to optimise that signal rather than `val_bpb`.
### Step 1 — Prepare your dataset
Run your Model B dataset builder (from `model-training.md`) and place the output where autoresearch can find it:
```bash
python scripts/build_dataset_b.py
# produces: data/model_b_train.jsonl, data/model_b_val.jsonl
```
### Step 2 — Modify `prepare.py` (one-time swap)
Replace the default nanochat data loading with your JSONL files. Edit only the data-loading section — leave everything else untouched:
```python
# In prepare.py — replace dataset loading section:
import json
def load_jsonl(path):
    with open(path) as f:
        return [json.loads(l) for l in f]
train_data = load_jsonl("data/model_b_train.jsonl")
val_data   = load_jsonl("data/model_b_val.jsonl")
# Tokenise prompt+completion pairs using the existing BPE tokeniser
# rest of prepare.py stays the same
```
### Step 3 — Replace the metric in `train.py`
Swap `val_bpb` for your quality scoring accuracy on the validation set. Add this eval function at the bottom of `train.py`:
```python
def eval_quality_accuracy(model, val_data, tokenizer, device):
    """
    Returns fraction of val examples where model output matches expected quality label.
    Used as the autoresearch metric — higher is better (invert sign for the loop).
    """
    correct = 0
    for ex in val_data[:100]:   # cap at 100 for speed within 5-min budget
        prompt = ex["prompt"]
        expected_score = json.loads(ex["completion"].split("<|assistant|>")[1])["score"]
        generated = generate(model, tokenizer, prompt, max_tokens=64, device=device)
        try:
            predicted_score = json.loads(generated)["score"]
            if abs(predicted_score - expected_score) <= 10:   # within 10 points = correct
                correct += 1
        except (json.JSONDecodeError, KeyError):
            pass
    return correct / min(100, len(val_data))
# Replace val_bpb reporting with:
# quality_acc = eval_quality_accuracy(model, val_data, tokenizer, device)
# val_metric = 1.0 - quality_acc   # autoresearch minimises, so invert
```
### Step 4 — Write `program.md` for Model B
```markdown
# Model B — Quality scorer optimisation
## Goal
Maximise quality scoring accuracy on the Webflow Restep validation set.
The metric is `val_metric` — lower is better (= 1 - accuracy, so minimise means maximise accuracy).
## Dataset
JSONL pairs of restep JSON → quality score JSON {"score": 0-100, "issues": [...]}.
~200 training examples, ~40 validation examples.
Sequences are longer than typical nanochat — average 600 tokens.
## What to optimise
- Increase context window if needed (sequences up to 1024 tokens)
- Tune learning rate: try range 1e-4 to 5e-4
- Adjust LoRA rank: r=16, 32, 64
- Batch size and gradient accumulation
- Warmup steps
## Rules
- DO NOT change the evaluation function
- DO NOT change data loading
- One change per experiment
- If val_metric does not improve, revert and try something else
- Log each experiment to results.tsv with: commit, val_metric, memory_gb, status, description
## Stopping criterion
If val_metric < 0.20 (= accuracy > 80%), the scorer is good enough to use as the autoresearch metric for Model C.
```
---
## Adapting autoresearch for Model C
Model C is the most valuable use of the loop. You want it to find the training configuration that produces the highest-quality, most valid restep JSON sequences.
### The metric
Two signals combined into one number:
```python
def eval_restep_quality(model, val_resteps, tokenizer, device, model_b_scorer):
    """
    For each val restep goal, generate a restep, then:
    1. Check JSON validity (0 or 1)
    2. Score with Model B quality scorer (0–100)
    Combined metric: mean(json_valid * quality_score / 100)
    Range: 0.0 (all invalid) to 1.0 (all valid + perfect quality)
    """
    scores = []
    for rs in val_resteps[:50]:
        goal = rs["description"]
        generated_text = generate(model, tokenizer, goal, max_tokens=1024, device=device)
        try:
            restep = json.loads(generated_text)
            quality = model_b_scorer(restep)["score"] / 100.0
            scores.append(quality)
        except json.JSONDecodeError:
            scores.append(0.0)   # invalid JSON = worst possible score
    return sum(scores) / len(scores)
# val_metric = 1.0 - eval_restep_quality(...)   # invert to minimise
```
### `program.md` for Model C
```markdown
# Model C — Restep sequence generator optimisation
## Goal
Maximise restep generation quality: produce structurally valid JSON resteps
that score highly when evaluated by the Model B quality scorer.
The metric is `val_metric` — lower is better
(= 1 - mean(json_validity × quality_score), so minimise = maximise quality).
## Dataset
~400 examples: goal description → full restep JSON.
Average sequence length: ~1200 tokens.
Training split: 360 examples. Validation split: 40 examples.
## Context
A restep is a structured sequence of Webflow DOM steps. Each step has:
- objective, intent, technicalDescription (string fields)
- element, action, selectorParameter, inputParameter, target (structured fields)
The model must learn to: follow the correct JSON schema, use only valid Webflow
elements (Container, Section, Div Block, Navbar, etc.), and produce logically
ordered steps that build toward the stated goal.
## What to optimise
Focus on things that help structured generation:
- Context window: sequences are long (~1200 tokens). Try 1024, 1536, 2048.
- LoRA rank: start r=64, try r=32 and r=128.
- Learning rate: try 5e-5 to 2e-4. Lower LR tends to help with structured output.
- Gradient accumulation: increase if GPU memory allows (effective batch size matters).
- Temperature at eval: try 0.05–0.3. Deterministic decoding often better for JSON.
## Rules
- DO NOT change the evaluation function or Model B scorer call
- DO NOT change data loading
- One experiment = one change to train.py
- If val_metric does not improve, hard revert to previous commit
- Log every experiment to results.tsv
## Priority experiments (try in this order)
1. Baseline run — record starting val_metric
2. Increase context window to 2048
3. Lower learning rate to 8e-5
4. Increase LoRA rank to 128
5. Adjust warmup (try 100, 200 steps)
6. After 20 experiments, re-read results.tsv and identify the best 3 changes.
   Combine them and test the combined configuration.
## Stopping criterion
Stop if val_metric < 0.15 (= mean quality score > 85%) or after 150 experiments.
Commit the best configuration found to a summary branch.
```
---
## Running the loop
```bash
# 1. Prepare data (one-time)
uv run prepare.py
# 2. Agree on a run tag with the agent (date-based)
# e.g. mar15-model-c
# 3. Create the experiment branch
git checkout -b autoresearch/mar15-model-c
# 4. Initialise results.tsv
echo -e "commit\tval_metric\tmemory_gb\tstatus\tdescription" > results.tsv
# 5. Launch the agent — point it at program.md and let it run
# Using Claude CLI (claude-code):
claude --dangerously-skip-permissions \
  "Read program.md, then begin the autoresearch experiment loop. \
   Run experiments autonomously. Do not stop to ask questions."
# Using any OpenAI-compatible agent:
# Point the agent at program.md with tool access to bash + file editing
```
The agent will now:
1. Read `program.md` and `train.py`
2. Make one targeted change to `train.py`
3. Run training for 5 minutes
4. Parse the metric from logs
5. If metric improved → `git commit` the change and log to `results.tsv`
6. If metric worsened → `git checkout train.py` (hard revert) and log as discarded
7. Repeat until you stop it or the stopping criterion in `program.md` is hit
---
## Reading the results
After an overnight run:
```bash
# See all experiments and outcomes
cat results.tsv | column -t -s $'\t'
# Count how many were kept vs discarded
grep -c "keep" results.tsv
grep -c "discard" results.tsv
# See the best performing commit
sort -k2 -n results.tsv | head -5
# Inspect what changes the agent made on kept commits
git log --oneline autoresearch/mar15-model-c
git show <commit-hash>   # see the exact train.py diff
```
A healthy overnight run looks like: ~100 experiments, ~15–25 kept. Each kept commit is a confirmed improvement on the validation metric.
---
## Integrating the best configuration
Once the loop finishes, extract the winning `train.py` and run a full training pass on it using your complete dataset:
```bash
# Checkout the best commit
git checkout <best-commit-hash> -- train.py
# Run full training (not time-boxed — use your SFTTrainer from model-training.md)
# Replace the autoresearch train.py hyperparams into your SFTConfig:
# - learning_rate, num_train_epochs, lora_r, max_seq_length, warmup_steps
# as identified by the agent
python scripts/train_model_c.py   # uses the winning hyperparams
```
---
## Running on DOKS as a Kubernetes Job
For overnight runs without a persistent Droplet, run autoresearch as a Kubernetes Job on the GPU node pool:
```yaml
# autoresearch-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: autoresearch-model-c
  namespace: inference
spec:
  template:
    spec:
      restartPolicy: Never
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
            sizeLimit: 16Gi
      containers:
        - name: autoresearch
          image: your-registry/autoresearch:latest
          env:
            - name: ANTHROPIC_API_KEY
              valueFrom:
                secretKeyRef:
                  name: llm-api-keys
                  key: anthropic
          command:
            - bash
            - -c
            - |
              git checkout -b autoresearch/$(date +%b%d)-model-c
              echo -e "commit\tval_metric\tmemory_gb\tstatus\tdescription" > results.tsv
              claude --dangerously-skip-permissions \
                "Read program.md then run the autoresearch loop autonomously."
          resources:
            requests:
              nvidia.com/gpu: 1
              memory: "32Gi"
              cpu: "8"
            limits:
              nvidia.com/gpu: 1
              memory: "48Gi"
              cpu: "16"
          volumeMounts:
            - name: models
              mountPath: /models
            - name: shm
              mountPath: /dev/shm
```
```bash
kubectl apply -f autoresearch-job.yaml
# Watch logs live
kubectl logs -f job/autoresearch-model-c -n inference
# When done, copy results out
kubectl cp inference/autoresearch-model-c:/app/results.tsv ./results-model-c.tsv
```
---
## Tips for writing effective `program.md` files
The bottleneck is not compute — it's your `program.md`. The better your instructions, the better the agent's experiments.
**Be specific about what to optimise first.** List 5–10 concrete starting experiments in priority order. The agent will still go off-script after exhausting them, but giving it a warm start saves the first 10 experiments from being random.
**Define the metric precisely.** The agent needs to know: what number to extract from logs, whether lower or higher is better, and what threshold counts as "good enough to stop".
**Set a revert rule.** Always include an explicit instruction to hard-revert on failure — `git checkout train.py`. Without this, the agent sometimes soft-reverts by undoing changes in a new commit, which makes the git history harder to read.
**Keep the program.md short.** The human iterates on `program.md` — this is the file you edit between sessions. After each overnight run, update it: remove experiments that failed consistently, add new ideas based on what worked, tighten the stopping criterion.
**Run multiple agents in parallel on separate branches.** Once you have two GPU nodes, point one agent at architecture changes and another at optimiser changes. Compare their `results.tsv` files in the morning.
---
## Relation to the rest of the training pipeline
```
model-training.md              autoresearch-training.md
─────────────────              ────────────────────────
Build dataset          →       Prepare dataset for loop
Train baseline model   →       Use baseline val_metric as starting point
                               Run overnight loop
                               Extract best hyperparams
                       →       Full training run with winning config
Upload adapter to Spaces →     Deploy via doks-gpu-inference.md
```
autoresearch sits between the baseline training run and the final production training run. It finds the best hyperparameters and architecture choices cheaply (5-minute experiments), which are then applied to a full training pass on the complete dataset.
