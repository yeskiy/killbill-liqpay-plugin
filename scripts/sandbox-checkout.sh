#!/usr/bin/env bash
# Copyright 2025-2026 Yehor Brodskyi
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Sandbox run on a local Kill Bill with this plugin: purchase with the LiqPay test card, SUCCESS through status polling, partial refund.
# Usage: scripts/sandbox-checkout.sh. Settings come from env vars, see the "Sandbox run" section of README.md.
set -euo pipefail
export MSYS_NO_PATHCONV=1

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
IMAGE="${PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf}"
NETWORK="${KB_NETWORK:-host}"

: "${LIQPAY_RESULT_URL:?set LIQPAY_RESULT_URL to a URL in resultUrlAllowlist of the tenant config}"
args=(--rm --network "$NETWORK" -v "$DIR/sandbox-checkout.cjs:/sandbox/sandbox-checkout.cjs:ro"
      -e LIQPAY_RESULT_URL -e SANDBOX_AMOUNT)
# Credentials go in by name or by env file, never as a value on the command line.
if [ -n "${KB_ENV_FILE:-}" ]; then
  [ -f "$KB_ENV_FILE" ] || { echo "FAIL  KB_ENV_FILE names no file" >&2; exit 1; }
  args+=(--env-file "$KB_ENV_FILE")
else
  args+=(-e KILLBILL_USER -e KILLBILL_PASSWORD -e KILLBILL_API_KEY -e KILLBILL_API_SECRET)
fi
[ -n "${KILLBILL_URL:-}" ] && args+=(-e KILLBILL_URL)
# NODE_PATH lets an image with Playwright in another folder skip the npm install.
[ -n "${NODE_PATH:-}" ] && args+=(-e NODE_PATH)

docker run "${args[@]}" --entrypoint sh "$IMAGE" -c '
  cd /sandbox
  if ! node -e "try{require.resolve(\"playwright\")}catch(e){require.resolve(\"@playwright/test\")}" >/dev/null 2>&1; then
    npm install --silent --no-audit --no-fund --no-save --prefix /sandbox playwright@1.64.1 >/dev/null
  fi
  exec node sandbox-checkout.cjs'
