# DostupVPN Android

Небольшое приложение: вход по токену, подключение к VLESS + Reality.

## Как устроено

- `net/` — API сервера (`/v1/session/start`, `heartbeat`, `stop`), вход по токену.
- `vpn/XrayConfig.kt` — собирает конфиг Xray: `tun` inbound → `vless` + Reality outbound, весь трафик через VPN.
- `vpn/DostupVpnService.kt` — поднимает системный TUN и передаёт его ядру **Xray-core**
  (библиотека [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), та же, что в v2rayNG).
  Собственный пакет исключён из VPN, поэтому сокеты ядра и запросы к API идут напрямую.
- Каждые `heartbeatSec` секунд отправляется heartbeat; при `410` сессия создаётся заново.

## Сборка

Workflow `.github/workflows/main.yml` скачивает готовый `libv2ray.aar` (версия в `LIBV2RAY_VERSION`),
оставляет в нём только arm64 и убирает geo-базы, затем собирает debug-APK
(артефакт `DostupVPN-debug-apk`).

## Диагностика (нужна debug-сборка и adb)

```powershell
adb exec-out run-as com.dostupvpn.app cat files/xray.log            # лог ядра Xray
adb exec-out run-as com.dostupvpn.app cat files/session-debug.txt   # параметры сессии (без секретов целиком)
adb exec-out run-as com.dostupvpn.app cat files/java-crash.log      # Java-падение
adb logcat -v time | Select-String "DostupVpnService|GoLog|AndroidRuntime"
```
