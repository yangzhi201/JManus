# JManus Helm Chart

This Helm chart deploys JManus, an AI Agent Management System, on Kubernetes.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.2.0+
- PV provisioner support in the underlying infrastructure (for persistence)

## Installing the Chart

To install the chart with the release name `my-release`:

```bash
helm install my-release ./jmanus
```

## Uninstalling the Chart

To uninstall/delete the `my-release` deployment:

```bash
helm delete my-release
```

## Configuration

The following table lists the configurable parameters of the JManus chart and their default values.

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Image repository | `jmanus/jmanus` |
| `image.tag` | Image tag | `latest` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |
| `image.pullSecrets` | Image pull secrets | `[]` |
| `app.name` | Application name | `jmanus` |
| `app.port` | Application port | `18080` |
| `app.profile` | Application profile (h2, mysql, postgres) | `h2` |
| `app.headless` | Enable headless mode for browser automation | `true` |
| `database.h2.enabled` | Enable H2 database | `true` |
| `database.h2.url` | H2 database URL | `jdbc:h2:file:./h2-data/openmanus_db;MODE=MYSQL;DATABASE_TO_LOWER=TRUE` |
| `database.h2.username` | H2 database username | `sa` |
| `database.h2.password` | H2 database password | `$FSD#@!@#!#$!12341234` |
| `database.mysql.enabled` | Enable MySQL database | `false` |
| `database.mysql.url` | MySQL database URL | `jdbc:mysql://your-mysql-host:3306/openmanus_db?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8` |
| `database.mysql.username` | MySQL database username | `your_mysql_username` |
| `database.mysql.password` | MySQL database password | `your_mysql_password` |
| `database.postgres.enabled` | Enable PostgreSQL database | `false` |
| `database.postgres.url` | PostgreSQL database URL | `jdbc:postgresql://localhost:5432/openmanus_db` |
| `database.postgres.username` | PostgreSQL database username | `postgres` |
| `database.postgres.password` | PostgreSQL database password | `123456` |
| `resources.limits.cpu` | CPU limit | `2` |
| `resources.limits.memory` | Memory limit | `4Gi` |
| `resources.requests.cpu` | CPU request | `1` |
| `resources.requests.memory` | Memory request | `2Gi` |
| `service.type` | Service type | `ClusterIP` |
| `service.port` | Service port | `18080` |
| `service.nodePort` | NodePort for NodePort service type | `` |
| `ingress.enabled` | Enable ingress | `false` |
| `ingress.className` | Ingress class name | `` |
| `ingress.annotations` | Ingress annotations | `{}` |
| `ingress.hosts` | Ingress hosts | `[{"host": "jmanus.local", "paths": [{"path": "/", "pathType": "Prefix"}]}]` |
| `ingress.tls` | Ingress TLS configuration | `[]` |
| `persistence.enabled` | Enable persistence | `true` |
| `persistence.storageClass` | Storage class name | `` |
| `persistence.accessModes` | Access modes | `["ReadWriteOnce"]` |
| `persistence.size` | Storage size | `10Gi` |
| `replicaCount` | Number of replicas | `1` |
| `env` | Additional environment variables | `{}` |
| `volumes` | Additional volumes | `[]` |
| `volumeMounts` | Additional volume mounts | `[]` |
| `nodeSelector` | Node selector | `{}` |
| `tolerations` | Tolerations | `[]` |
| `affinity` | Affinity | `{}` |

### Database Configuration

The chart supports three database types: H2 (default), MySQL, and PostgreSQL. To use a specific database, set the `app.profile` value and configure the corresponding database settings.

For example, to use MySQL:

```yaml
app:
  profile: mysql

database:
  mysql:
    enabled: true
    url: "jdbc:mysql://your-mysql-host:3306/openmanus_db?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8"
    username: "your_mysql_username"
    password: "your_mysql_password"
```

### Ingress Configuration

To enable ingress, set `ingress.enabled` to `true` and configure the ingress settings:

```yaml
ingress:
  enabled: true
  className: "nginx"
  hosts:
    - host: jmanus.example.com
      paths:
        - path: /
          pathType: Prefix
```

### Persistence

Persistence is enabled by default. To disable it, set `persistence.enabled` to `false`. You can also configure the storage class and size:

```yaml
persistence:
  enabled: true
  storageClass: "fast-ssd"
  size: 20Gi
```

## Notes

- The chart uses ConfigMap to manage application configuration files.
- Database credentials are stored in a Secret.
- The application is configured to run in headless mode for browser automation by default.
- The chart supports Kubernetes version 1.19 and above.