# Codex Link Web Tunnel

Remote access URL:

```text
https://www.sitesindevelopment.com/codex-link/
```

Android endpoint:

```text
https://www.sitesindevelopment.com/codex-link/index.php/link
```

The clean `/codex-link/link` path is currently intercepted by the host, so the Android app uses the reliable `index.php` route.

## Start Tunnel

Use the Desktop shortcut:

```text
C:\Users\Gabe\Desktop\Codex Link Web Tunnel.lnk
```

Or run:

```powershell
D:\__MY APPS\codex link for android\desktop_bridge\start-codex-link-web-tunnel.ps1
```

The launcher starts the local bridge on `127.0.0.1:18765` if needed, then starts the polling tunnel that connects the public relay to the local bridge.

## Tokens

Remote relay tokens are stored locally here:

```text
D:\__MY APPS\codex link for android\desktop_bridge\remote_tunnel_config.json
```

That private file is ignored by git. The public repo includes a non-secret template at:

```text
D:\__MY APPS\codex link for android\desktop_bridge\remote_tunnel_config.sample.json
```

Use the `phoneToken` value as the Android pairing token when `Web Link` mode is selected. Do not paste these tokens into public files or chat logs.

For public APK testing, the relay can also accept a short-lived `testPhoneToken`. The deploy script can generate and upload one with:

```powershell
python remote_relay\deploy_codex_link_relay.py --new-test-phone-token --test-token-hours 24
```

The server checks `testPhoneTokenExpiresAt` before accepting the token.

## Verification

With the tunnel running, this should return bridge health through the public relay:

```powershell
$config = Get-Content -Raw 'D:\__MY APPS\codex link for android\desktop_bridge\remote_tunnel_config.json' | ConvertFrom-Json
$headers = @{ Authorization = 'Bearer ' + $config.phoneToken }
Invoke-RestMethod -Uri 'https://www.sitesindevelopment.com/codex-link/index.php/health' -Headers $headers
```
