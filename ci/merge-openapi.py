#!/usr/bin/env python3
"""Generates docs/openapi.yaml - the service's SINGLE published API document
(OpenAPI 3.2.0, YAML): every endpoint of the embedded engine's REST API plus this
service's own additions (the incident operations and the management-port endpoints).

The engine half is never written by hand: it comes from the engine's own generated
spec artifact (org.cadenzaflow.bpm:cadenzaflow-engine-rest-openapi, version taken
from <cadenzaflow.version> in pom.xml), so the file is regenerated - not edited -
whenever the engine version bumps:

    python ci/merge-openapi.py        # requires: python3 + pyyaml, mvn on PATH

What the merge does, and why:
- Upstream paths are rooted at the engine app ('/incident', ...); they are prefixed
  with '/engine-rest' so every path in the merged spec is relative to the service's
  servlet context, like the fragment's own paths.
- The fragment's generic '/engine-rest/{enginePath}' passthrough is dropped
  here: the merged spec enumerates the real engine paths, which is its point.
- The output is OpenAPI 3.2.0. The upstream spec is 3.0.2, so its schemas are
  UP-converted on the way in: `type: x, nullable: true` becomes the JSON-Schema
  union `type: [x, 'null']` (audited: every upstream nullable carries a scalar
  type; boolean exclusiveMinimum/Maximum does not occur). `format: binary` stays -
  formats are open-ended annotations in JSON Schema. The service fragment already
  speaks the union style natively.
- Global security becomes the deployed reality (bearerJwt via openid-rbac-security);
  the upstream 'basicAuth' scheme is kept in components for reference but is not
  active at the edge. The '/engine-rest/external-task/**' whitelist exception is a
  deployment concern documented in the per-path descriptions and README §3.
- The upstream templated servers (including the named-engine variant) are replaced
  by this service's two real servers; the service embeds exactly one engine.
"""

import json
import pathlib
import re
import subprocess
import sys
import zipfile

try:
    import yaml
except ImportError:
    sys.exit("pyyaml is required: pip install pyyaml")

ROOT = pathlib.Path(__file__).resolve().parent.parent
SERVICE_SPEC = ROOT / "ci" / "openapi-service.yaml"
OUTPUT = ROOT / "docs" / "openapi.yaml"
ARTIFACT = "org.cadenzaflow.bpm:cadenzaflow-engine-rest-openapi"


def engine_version() -> str:
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<cadenzaflow\.version>([^<]+)</cadenzaflow\.version>", pom)
    if not match:
        sys.exit("cadenzaflow.version not found in pom.xml")
    return match.group(1)


def upstream_spec(version: str) -> dict:
    jar = pathlib.Path.home().joinpath(
        ".m2", "repository", "org", "cadenzaflow", "bpm",
        "cadenzaflow-engine-rest-openapi", version,
        f"cadenzaflow-engine-rest-openapi-{version}.jar")
    if not jar.exists():
        print(f"fetching {ARTIFACT}:{version} ...")
        subprocess.run(
            ["mvn", "-q", "dependency:get", f"-Dartifact={ARTIFACT}:{version}"],
            check=True, cwd=ROOT, shell=(sys.platform == "win32"))
    with zipfile.ZipFile(jar) as z, z.open("openapi.json") as f:
        return json.load(f)


def up_convert_nullable(node):
    """OpenAPI 3.0 `type: x, nullable: true` -> JSON-Schema `type: [x, 'null']`."""
    if isinstance(node, dict):
        if node.get("nullable") is True and isinstance(node.get("type"), str):
            node = {k: v for k, v in node.items() if k != "nullable"}
            node["type"] = [node["type"], "null"]
        return {k: up_convert_nullable(v) for k, v in node.items()}
    if isinstance(node, list):
        return [up_convert_nullable(v) for v in node]
    return node


def main():
    version = engine_version()
    upstream = up_convert_nullable(upstream_spec(version))
    service = yaml.safe_load(SERVICE_SPEC.read_text(encoding="utf-8"))

    merged = {
        "openapi": "3.2.0",
        "info": {
            "title": "opentmf-cadenzaflow — complete API surface",
            "version": service["info"]["version"],
            "description": (
                "GENERATED FILE — regenerate with ci/merge-openapi.py, do not edit. "
                f"The union of the embedded CadenzaFlow engine's REST API ({version}, "
                f"from {ARTIFACT}) rebased under /engine-rest, and this service's own "
                "additions (incident operations, management endpoints) from the "
                "hand-maintained source fragment ci/openapi-service.yaml. "
                "Security reflects the deployed edge: "
                "bearer JWT on everything except the documented whitelist "
                "(/engine-rest/external-task/** by default) and the anonymous "
                "management-port endpoints."
            ),
            "contact": service["info"].get("contact"),
            "license": service["info"].get("license"),
        },
        "servers": service["servers"],
        "security": [{"bearerJwt": []}],
        # Service tags FIRST: tag-aware UIs (Swagger UI, Redoc) render groups in
        # declaration order, so the service's extensions stand above the ~40
        # engine groups instead of drowning among them.
        "tags": [t for t in service.get("tags", []) if t["name"] != "engine-rest"]
            + upstream.get("tags", []),
        "paths": {},
        "components": {},
    }

    for path, item in upstream["paths"].items():
        merged["paths"]["/engine-rest" + path] = item
    for path, item in service["paths"].items():
        if path == "/engine-rest/{enginePath}":
            continue  # the passthrough placeholder; the real paths are enumerated now
        if path in merged["paths"]:
            sys.exit(f"path collision with upstream: {path}")
        merged["paths"][path] = item

    up_components = upstream.get("components", {})
    svc_components = service.get("components", {})
    for section in sorted(set(up_components) | set(svc_components)):
        a, b = up_components.get(section, {}), svc_components.get(section, {})
        clash = set(a) & set(b)
        if clash:
            sys.exit(f"components/{section} collision with upstream: {sorted(clash)}")
        merged["components"][section] = {**a, **b}

    OUTPUT.write_text(
        yaml.safe_dump(merged, sort_keys=False, allow_unicode=True, width=100),
        encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(merged['paths'])} paths, "
          f"{len(merged['components'].get('schemas', {}))} schemas "
          f"(engine {version} + service surface)")


if __name__ == "__main__":
    main()
