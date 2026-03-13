# Software Development Best Practices

A comprehensive guide covering general software development principles and technology-specific best practices for Spring Boot, Angular/React, and Kubernetes.

---

## Table of Contents

1. [General Software Development](#general-software-development)
2. [Spring Boot](#spring-boot)
3. [Angular](#angular)
4. [React](#react)
5. [Kubernetes](#kubernetes)
6. [CI/CD & DevOps](#cicd--devops)
7. [Security](#security)

---

## General Software Development

### Code Quality
- Follow **SOLID** principles (Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion).
- Prefer **composition over inheritance** to keep code flexible and testable.
- Keep functions and methods **small and focused** — a function should do one thing well.
- Write **self-documenting code**: use meaningful variable, function, and class names that eliminate the need for comments.
- Add comments only to explain **why**, not **what** — the code itself should explain what it does.
- Apply the **DRY** (Don't Repeat Yourself) principle; avoid duplicating logic across the codebase.
- Follow the **YAGNI** (You Aren't Gonna Need It) principle; don't add features until they are needed.

### Version Control (Git)
- Write **clear, atomic commit messages** following the Conventional Commits specification (e.g., `feat:`, `fix:`, `docs:`, `refactor:`).
- Keep commits **small and focused** on a single logical change.
- Use **feature branches** and never commit directly to `main`/`master`.
- Perform **code reviews** via pull/merge requests before merging.
- Protect the main branch with **branch protection rules** (required reviews, passing CI checks).
- Rebase or squash commits before merging to keep history clean.
- Tag releases using **semantic versioning** (MAJOR.MINOR.PATCH).

### Testing
- Follow the **testing pyramid**: many unit tests, fewer integration tests, minimal end-to-end tests.
- Aim for **high code coverage** (80%+) without treating coverage as the sole quality metric.
- Write tests **before or alongside** code (TDD/BDD where appropriate).
- Keep tests **independent, repeatable, and fast**.
- Use **mocks and stubs** to isolate units under test.
- Include **negative test cases** and edge cases.

### Documentation
- Maintain an up-to-date `README.md` with setup, configuration, and usage instructions.
- Document **architectural decisions** using Architecture Decision Records (ADRs).
- Keep API documentation current using tools like OpenAPI/Swagger.
- Use diagrams (C4 model, sequence diagrams) to convey system design.

---

## Spring Boot

### Project Structure
- Follow the **package-by-feature** structure rather than package-by-layer to improve cohesion:
  ```
  com.example.app
  ├── order/
  │   ├── OrderController.java
  │   ├── OrderService.java
  │   ├── OrderRepository.java
  │   └── Order.java
  └── user/
      ├── UserController.java
      └── ...
  ```
- Keep the main application class in the **root package** so component scanning covers all sub-packages.

### Configuration
- Externalize all configuration using `application.yml` / `application.properties` — never hardcode values.
- Use **Spring Profiles** (`dev`, `test`, `staging`, `prod`) to manage environment-specific configuration.
- Store secrets in a **secret manager** (AWS Secrets Manager, HashiCorp Vault, Kubernetes Secrets) — never in source control.
- Use `@ConfigurationProperties` classes to bind and validate configuration in a type-safe way:
  ```java
  @ConfigurationProperties(prefix = "app.payment")
  @Validated
  public class PaymentProperties {
      @NotBlank
      private String apiKey;
      // ...
  }
  ```
- Set sensible **connection pool** and **timeout** values for all external resources.

### Dependency Injection & Beans
- Prefer **constructor injection** over field injection (`@Autowired` on fields) for testability and immutability.
- Mark service and repository beans as `final` when using constructor injection.
- Use `@Service`, `@Repository`, and `@Component` annotations appropriately to convey intent.
- Avoid circular dependencies — they indicate a design problem.

### REST API Design
- Follow **RESTful conventions**: use nouns for resources, HTTP verbs for actions (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`).
- Return appropriate **HTTP status codes** (`200`, `201`, `204`, `400`, `404`, `409`, `500`).
- Version your APIs from day one: `/api/v1/orders`.
- Use **DTOs** (Data Transfer Objects) to decouple the API contract from the domain model.
- Validate request bodies with `@Valid` and Bean Validation annotations (`@NotNull`, `@Size`, etc.).
- Implement a **global exception handler** with `@RestControllerAdvice` for consistent error responses.
- Apply **pagination** for collection endpoints using `Pageable`.

### Data & Persistence
- Use **Spring Data JPA** repositories; avoid writing boilerplate JDBC where JPA suffices.
- Never expose JPA entities directly through the API — use DTOs and mappers (MapStruct).
- Use **database migrations** (Flyway or Liquibase) to manage schema changes in a versioned, repeatable way.
- Apply `@Transactional` at the **service layer**, not the repository or controller layer.
- Use **projections** or native queries for read-heavy operations to avoid loading unnecessary data.
- Enable **lazy loading** as the default and use explicit `JOIN FETCH` where needed.
- Index foreign keys and frequently queried columns.

### Performance
- Use **caching** (`@Cacheable`, Spring Cache with Redis/Caffeine) for expensive or frequently read data.
- Use **async processing** (`@Async`, reactive streams, message queues) for long-running tasks.
- Monitor and tune **HikariCP** connection pool settings for your workload.
- Enable **HTTP compression** and set proper `Cache-Control` headers for static resources.

### Observability
- Integrate **Spring Boot Actuator** and expose health, metrics, and info endpoints.
- Use **structured logging** (JSON format) with a correlation/trace ID in every log entry.
- Integrate with **Micrometer** and export metrics to Prometheus/Grafana.
- Implement distributed tracing with **Micrometer Tracing** (OpenTelemetry).
- Define meaningful health indicators for downstream dependencies (DB, cache, external APIs).

### Testing in Spring Boot
- Use `@SpringBootTest` sparingly — prefer **slice tests** (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`) for focused, fast tests.
- Mock external dependencies with `@MockBean` in integration tests.
- Use **Testcontainers** for integration tests that require real databases or message brokers.
- Test REST controllers with `MockMvc` or `WebTestClient`.
- Use `@ActiveProfiles("test")` to load test-specific configuration.

---

## Angular

### Project Structure
- Organize code into **feature modules** to enable lazy loading and clear boundaries:
  ```
  src/app/
  ├── core/          # Singleton services, guards, interceptors
  ├── shared/        # Reusable components, pipes, directives
  └── features/
      ├── dashboard/
      └── orders/
  ```
- Use the **Angular CLI** to generate components, services, and modules consistently.
- Keep the `AppModule` lean — move feature logic into dedicated modules.

### Components
- Follow the **Smart/Dumb (Container/Presentational) component pattern**: smart components manage state and data fetching; dumb components only receive inputs and emit events.
- Use `OnPush` change detection strategy on presentational components for better performance.
- Unsubscribe from Observables using `takeUntilDestroyed()`, `async` pipe, or `Subject` + `takeUntil` to prevent memory leaks.
- Keep templates **simple** — move complex logic into the component class or a pipe.
- Prefer the `async` pipe over manual subscriptions in templates.

### State Management
- Use **signals** (Angular 17+) for local and shared reactive state.
- For complex global state use **NgRx** or **Akita** with a clear action/reducer/effect pattern.
- Keep state **normalized** (flat) to avoid deep nesting and redundancy.
- Derive computed/view state with selectors rather than storing derived data.

### Services & HTTP
- Place all HTTP calls in **dedicated services**, never in components.
- Use **HTTP interceptors** for cross-cutting concerns: authentication headers, error handling, logging.
- Use **RxJS operators** (`switchMap`, `catchError`, `retry`) to compose async operations.
- Cache HTTP responses where appropriate using `shareReplay`.

### Routing
- Implement **lazy loading** for all feature modules to reduce the initial bundle size.
- Protect routes with **guards** (`CanActivate`, `CanDeactivate`, `Resolve`).
- Use **resolvers** to pre-fetch data before activating a route.

### Forms
- Use **Reactive Forms** over Template-Driven Forms for complex, dynamic, or tested forms.
- Define validators at the form control level and surface errors consistently.
- Reuse **custom validators** and **form group factories** across the application.

### Performance
- Enable **lazy loading** for images using `loading="lazy"`.
- Use `trackBy` in `*ngFor` directives to minimize DOM re-renders.
- Apply `OnPush` change detection to reduce the number of checks.
- Analyze bundle size with `ng build --stats-json` and `webpack-bundle-analyzer`.
- Use Angular's built-in **preloading strategies** (`PreloadAllModules`) for subsequent routes.

### Testing in Angular
- Write unit tests with **Jest** or **Karma/Jasmine** for components, services, and pipes.
- Use `TestBed` for component tests and mock dependencies with `jasmine.createSpyObj` or `jest.fn()`.
- Write end-to-end tests with **Cypress** or **Playwright**.
- Test components in isolation using **shallow rendering** where possible.

---

## React

### Project Structure
- Co-locate files by **feature or domain**, not by file type:
  ```
  src/features/
  └── orders/
      ├── OrderList.tsx
      ├── OrderList.test.tsx
      ├── useOrders.ts
      ├── ordersSlice.ts
      └── ordersApi.ts
  ```
- Use an **index.ts** barrel file to expose the public API of each feature.

### Components
- Prefer **functional components** with Hooks over class components.
- Keep components **small and focused** — extract sub-components when a component grows too large.
- Follow the **Single Responsibility Principle**: separate data fetching, business logic, and rendering.
- Use **named exports** for components to improve refactoring and tree-shaking.

### State Management
- Use **local state** (`useState`) for UI state that does not need to be shared.
- Lift state up to the **lowest common ancestor** before reaching for global state.
- Use **Context API** for low-frequency, cross-cutting state (theme, auth).
- For complex global state use **Redux Toolkit**, **Zustand**, or **Jotai**.
- Use **React Query** (TanStack Query) or **SWR** for server state (data fetching, caching, synchronization) instead of storing API responses in Redux.

### Hooks
- Extract reusable logic into **custom hooks** (`useOrders`, `useAuth`).
- Follow the **Rules of Hooks**: only call hooks at the top level, only in React functions.
- Use `useCallback` and `useMemo` only when profiling confirms a performance problem — avoid premature optimization.
- Use `useEffect` carefully: always specify the dependency array, clean up subscriptions and timers.

### Performance
- Use **React.memo** for pure components that render often with the same props.
- Use **code splitting** with `React.lazy` and `Suspense` for route-level components.
- Avoid **anonymous functions and objects** in JSX props — they create new references on every render.
- Profile with **React DevTools Profiler** before optimizing.
- Use **virtual lists** (react-window, react-virtual) for long lists.

### TypeScript
- Use TypeScript for all React projects — type all props, state, and return values.
- Prefer **interfaces** for component props and **type aliases** for unions/intersections.
- Avoid `any` — use `unknown` and narrow the type explicitly.
- Use **discriminated unions** for complex state machines.

### Testing in React
- Use **React Testing Library** with Jest — test behavior, not implementation details.
- Query elements by **accessible roles and labels**, not by CSS classes or test IDs.
- Use **MSW (Mock Service Worker)** to mock API calls in tests.
- Write **integration tests** that cover complete user flows.
- Use **Storybook** to develop and visually test UI components in isolation.

---

## Kubernetes

### Resource Definitions
- Store all Kubernetes manifests in **version control** alongside the application code.
- Use **Helm charts** or **Kustomize** to manage environment-specific configuration without duplicating manifests.
- Always specify explicit **image tags** — never use `latest` in production.
- Set **resource requests and limits** for every container to enable proper scheduling and prevent noisy-neighbour issues:
  ```yaml
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "500m"
      memory: "512Mi"
  ```

### High Availability & Reliability
- Run at least **2–3 replicas** for production workloads.
- Configure **Pod Disruption Budgets (PDB)** to ensure a minimum number of pods remain available during node maintenance.
- Use **Pod Anti-Affinity** rules to spread replicas across nodes and availability zones.
- Configure **Horizontal Pod Autoscaler (HPA)** based on CPU/memory or custom metrics.
- Use **Vertical Pod Autoscaler (VPA)** in recommendation mode to fine-tune resource requests.
- Set **`minReadySeconds`** and **`progressDeadlineSeconds`** on Deployments to detect rollout failures.

### Health Checks
- Define **`livenessProbe`** to restart unhealthy containers (use sparingly — incorrect liveness probes cause restart loops).
- Define **`readinessProbe`** to prevent traffic from reaching pods that are not yet ready.
- Define **`startupProbe`** for slow-starting applications to avoid premature liveness failures:
  ```yaml
  readinessProbe:
    httpGet:
      path: /actuator/health/readiness
      port: 8080
    initialDelaySeconds: 10
    periodSeconds: 5
  livenessProbe:
    httpGet:
      path: /actuator/health/liveness
      port: 8080
    initialDelaySeconds: 30
    periodSeconds: 10
  ```

### Configuration & Secrets
- Use **ConfigMaps** for non-sensitive configuration and **Secrets** for sensitive data.
- Never hardcode secrets in container images or manifests.
- Integrate with a secret management system (HashiCorp Vault, AWS Secrets Manager) using a secrets store CSI driver or operator.
- Rotate Secrets regularly and audit access.

### Networking
- Use **Services** of the correct type: `ClusterIP` for internal, `NodePort`/`LoadBalancer` for external, or an **Ingress** controller with TLS termination.
- Enforce **NetworkPolicies** to restrict pod-to-pod traffic to only what is necessary (default-deny approach).
- Use **service meshes** (Istio, Linkerd) for mTLS, traffic shaping, and observability in complex microservice environments.

### Security
- Run containers as a **non-root user** and with a read-only root filesystem where possible.
- Set `allowPrivilegeEscalation: false` and drop unnecessary Linux capabilities.
- Use **Pod Security Standards** (`restricted` policy) or **OPA/Gatekeeper** to enforce policies.
- Scan container images for vulnerabilities using **Trivy**, **Snyk**, or similar tools in CI.
- Use **Role-Based Access Control (RBAC)** with the principle of least privilege for all service accounts.
- Enable **audit logging** on the API server.

### Observability
- Use the **sidecar pattern** or DaemonSets for log collection (Fluent Bit → Elasticsearch/Loki).
- Export metrics with **Prometheus** and visualize with **Grafana**.
- Implement distributed tracing with **Jaeger** or **Tempo** (OpenTelemetry).
- Set up **alerts** for pod crash loops, high error rates, and resource saturation.

### Deployment Strategy
- Use **RollingUpdate** strategy with `maxUnavailable: 0` and `maxSurge: 1` for zero-downtime deployments.
- Use **blue/green** or **canary** deployments (via Argo Rollouts or Flagger) for high-risk changes.
- Implement **GitOps** with ArgoCD or Flux to drive cluster state from Git.
- Always test changes in a staging environment before promoting to production.

### Storage
- Use **PersistentVolumeClaims (PVC)** with appropriate StorageClass for stateful workloads.
- Prefer **StatefulSets** over Deployments for stateful applications.
- Back up PersistentVolumes regularly and test restore procedures.
- Use **ReadOnlyMany** or **ReadWriteOnce** access modes appropriately.

---

## CI/CD & DevOps

- Automate the **build, test, and deploy pipeline** for every code change.
- Run **static analysis** (linting, SAST) and **unit tests** on every pull request.
- Build **Docker images** in CI and push to a private registry with immutable tags.
- Promote the **same artifact** through environments — never rebuild for staging or production.
- Use **infrastructure as code** (Terraform, Pulumi) for all cloud resources.
- Implement **feature flags** to decouple deployment from feature release.
- Enforce **quality gates**: fail the pipeline if coverage drops, vulnerabilities are found, or performance regresses.

---

## Security

- Apply the **principle of least privilege** everywhere: users, services, pods, IAM roles.
- Validate and sanitize **all inputs** from external sources to prevent injection attacks (SQL, XSS, SSRF).
- Use **parameterized queries** or ORMs — never concatenate user input into SQL strings.
- Enforce **HTTPS/TLS** for all communication; use HSTS in production.
- Store passwords with a strong adaptive hash function (**bcrypt**, **Argon2**).
- Use short-lived **JWT tokens** and rotate **refresh tokens** on use.
- Implement proper **CORS policies** — do not use wildcard origins in production.
- Conduct **dependency audits** (`npm audit`, `mvn dependency-check`) regularly and keep dependencies updated.
- Perform **threat modelling** during design and **penetration testing** before major releases.
- Have an **incident response plan** and conduct post-mortems after security incidents.

---

*Last updated: 2026-03-13*
