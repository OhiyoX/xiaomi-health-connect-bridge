# Product demo and screenshots

This is the capture checklist for the public README. Use **real running-app screenshots**, not design prototypes or simulated health records presented as actual results.

## Recommended gallery

1. **Health overview** — Android app showing supported metrics and recent records. Crop personal identifiers and sensitive values.
2. **Health Connect sync** — app sync status together with a Health Connect permissions or data-source view, demonstrating the integration.
3. **On-device MCP** — Android MCP service running and a compatible same-device client successfully listing tools or querying sample/test data.
4. **Permission control** — client authorization screen showing selected scopes/tools and an approval decision, with secrets and client identifiers redacted.

Optional fifth image: Docker administration view, clearly labeled **optional server deployment**.

## Capture rules

- Use actual screenshots from a built and running version; preserve app chrome and do not fabricate UI states.
- Use synthetic/test health records where possible, or redact personal measurements and timestamps.
- Never show credentials, OAuth codes, tokens, QR pairing secrets, internal addresses or private account identifiers.
- Capture portrait images at a consistent width; avoid giant full-resolution screenshots in the README.
- Save optimized images under `docs/media/` as `health-overview.webp`, `health-connect-sync.webp`, `on-device-mcp.webp`, `permissions.webp`.
- If an image comes from `docs/admin-ui-proto/screenshots/`, label it **UI prototype**; those assets do not prove the app behaves as depicted.

## Suggested 60-second demo

1. Open Mi Fitness briefly to establish that it remains installed and usable.
2. Open the bridge and show sync status.
3. Show the synced source in Health Connect.
4. Start the Android MCP service and connect a same-device MCP client.
5. Ask for a simple trend using test records.
6. Open client permissions and show how access can be approved or restricted.

The video should demonstrate the same four promises as the README: keep Mi Fitness, Health Connect interoperability, on-device MCP, and controlled access.

## README publishing gate

Only add a screenshot to the README after checking that the capture reflects the current release. Include alt text, a short caption, and the app version or commit used to capture it. Do not add placeholder image links that render as broken images.
