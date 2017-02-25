# opa-linkerd-authz

Experimental policy-enabled linkerd identifier that enforces auth/z policy.

## Building

```
./sbt assembly  # puts JAR under target/scala-2.11/
```

## Running (local)

Download and untar linkerd-0.9.0, change into directory.

1. `export L5D_HOME=$PWD` sets environment variable that linkerd-0.9.0-exec script uses to load plugins.
1. `mkdir -p plugins` creates directory to stick plugin JARs into.
1. Copy JAR file into `plugins` directory.
1. Create linkerd configuration:

  ```bash
  cat > config/opa_linkerd_example.yaml <<EOF
  routers:
  - protocol: http
    dtab: |
      /host     => /#/io.l5d.fs;
      /method   => /$/io.buoyant.http.anyMethodPfx/host;
      /http/1.1 => /method;
    httpAccessLog: logs/access.log
    label: int
    dstPrefix: /http
    identifier:
      kind: org.openpolicyagent.linkerd.authzIdentifier
    servers:
    - port: 4140
      ip: 0.0.0.0

  namers:
  - kind: io.l5d.fs
    rootDir: disco
  EOF
  ```

1. Run linkerd:

  ```bash
  ./linkerd-0.9.0-exec config/opa_linkerd_example.yaml
  ```

## Manual Testing (local)

1. Start a simple webserver:

  ```bash
  python -m SimpleHTTPServer 9999
  ```

1. Start OPA:

  ```bash
  opa run -s --v=2 --logtostderr=1
  ```

1. Define simple policy:

  ```ruby
  cat >example.rego <<EOF
  package linkerd_experiment

  import input.method
  import input.path

  default allow = false

  allow {
      method = "GET"
      not contains(path, "deadbeef")
  }
  EOF
  ```

1. Push policy into OPA:

  ```bash
  curl -X PUT http://localhost:8181/v1/policies/test --data-binary @example.rego
  ```

  and then (optional):

  ```
  fswatch -o example.rego | xargs -n1 \
    curl -X PUT http://localhost:8181/v1/policies/test --data-binary @example.rego
  ```

1. GET some document from webserver via linkerd:

  ```bash
  curl -H 'Host: web' localhost:4140
  ```

1. Try to POST some document to webserver via linkerd:

  ```bash
  curl -d 'hooray' -H 'Host: web' localhost:4140
  ```

That's it! 🎉

## TODO

- Flesh out support for HTTP fields
- Implement support for other transports
- Refactor to use configuration for OPA endpoint
