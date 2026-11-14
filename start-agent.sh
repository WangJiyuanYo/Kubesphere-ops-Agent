#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
agent_java=java
if [[ -n "${JAVA_HOME:-}" ]]; then agent_java="$JAVA_HOME/bin/java"; fi
exec "$agent_java" -jar target/deployment-ops-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=sqlite --server.address=127.0.0.1 --server.port=8100 --agent.mode=DEMO --agent.auto-retry=false --agent.platform-catalog.enabled=false --agent.provisioning.enabled=false --agent.kubectl.enabled=false
