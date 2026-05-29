#!/bin/sh
set -eu

# 透過 Docker 內網連到 java-gateway，不可使用 localhost。
SUBDOMAIN="${LOCALTUNNEL_SUBDOMAIN:-}"
if [ -z "$SUBDOMAIN" ]; then
  echo "ERROR: LOCALTUNNEL_SUBDOMAIN 未設定，請在 .env 指定唯一 subdomain。" >&2
  exit 1
fi

echo "LocalTunnel 啟動中，請求固定 subdomain: ${SUBDOMAIN}"

# localtunnel 若 subdomain 已被全球其他使用者占用，會靜默改配隨機網址。
npx localtunnel --port 8080 --local-host java-gateway --subdomain "${SUBDOMAIN}" 2>&1 | while IFS= read -r line; do
  printf '%s\n' "$line"

  case "$line" in
    *"your url is:"*)
      url=$(printf '%s\n' "$line" | sed -n 's/.*your url is: //p')
      case "$url" in
        *"${SUBDOMAIN}.loca.lt"*)
          echo "INFO: 固定隧道已建立: ${url}"
          echo "INFO: GitHub Payload URL: ${url}/api/webhooks/github"
          ;;
        *)
          echo "WARN: 請求的 subdomain '${SUBDOMAIN}' 無法使用（可能已被占用）。" >&2
          echo "WARN: LocalTunnel 改配隨機網址: ${url}" >&2
          echo "WARN: 請在 .env 更換 LOCALTUNNEL_SUBDOMAIN 為更唯一的名稱後重建 localtunnel。" >&2
          ;;
      esac
      ;;
  esac
done
