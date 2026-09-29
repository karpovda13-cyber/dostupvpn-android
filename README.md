# DostupVPN Android

Небольшое приложение: вход по токену, подключение к VLESS + Reality.

## Как устроено

- `net/` — API сервера (`/v1/session/start`, `heartbeat`, `stop`), вход по токену.
- `vpn/XrayConfig.kt` — собирает конфиг Xray: `tun` inbound → `vless` + Reality outbound + маршрутизация по странам.
- `vpn/DostupVpnService.kt` — поднимает системный TUN и передаёт его ядру **Xray-core**
  (библиотека [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), та же, что в v2rayNG).
  Собственный пакет исключён из VPN, поэтому сокеты ядра и запросы к API идут напрямую.
- Каждые `heartbeatSec` секунд отправляется heartbeat; при `410` сессия создаётся заново.

## Сборка

Workflow `.github/workflows/main.yml` скачивает готовый `libv2ray.aar` (версия в `LIBV2RAY_VERSION`),
оставляет в нём только arm64 и убирает полные geo-базы (свои урезанные лежат в `app/src/main/assets/geo`), затем собирает debug-APK
(артефакт `DostupVPN-debug-apk`).

## Диагностика (нужна debug-сборка и adb)

```powershell
adb exec-out run-as com.dostupvpn.app cat files/xray.log            # лог ядра Xray
adb exec-out run-as com.dostupvpn.app cat files/session-debug.txt   # параметры сессии (без секретов целиком)
adb exec-out run-as com.dostupvpn.app cat files/java-crash.log      # Java-падение
adb logcat -v time | Select-String "DostupVpnService|GoLog|AndroidRuntime"
```

## Подпись (чтобы приложение обновлялось поверх старой версии)

Все сборки должны подписываться одним и тем же ключом. Ключ хранится в секретах репозитория
(Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | файл `.keystore`, закодированный в base64 |
| `SIGNING_STORE_PASSWORD` | пароль хранилища |
| `SIGNING_KEY_ALIAS` | алиас ключа (например `dostupvpn`) |
| `SIGNING_KEY_PASSWORD` | пароль ключа (для PKCS12 совпадает с паролем хранилища) |

Создание ключа (один раз, на своём компьютере):

```powershell
keytool -genkeypair -v -keystore dostupvpn.keystore -alias dostupvpn -keyalg RSA -keysize 2048 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("dostupvpn.keystore")) | Set-Clipboard
```

**Сохраните `dostupvpn.keystore` и пароли в надёжном месте** (менеджер паролей). Потерянный ключ
восстановить нельзя: обновить приложение поверх установленного станет невозможно.
В логе CI шаг «Show signing certificate» печатает SHA-256 сертификата — он должен совпадать между сборками.

## Маршрутизация по странам

Напрямую (мимо VPN) идёт: российские домены (`.ru`, `.su`, `.рф`, списки `geosite:category-ru`,
Яндекс, VK, Сбер, Ozon и др.), российские IP (`geoip:ru`) и локальные сети. Остальное — через VPN.
Порядок правил (первое совпавшее): правила админа «через VPN» → правила админа «напрямую» →
локальные сети → российские домены → российские IP → всё остальное в VPN.

Базы `geoip.dat` / `geosite.dat` вшиты в APK и урезаны до российских списков (≈450 КБ вместо 28 МБ).
Обновить их (например, вместе с новой версией libv2ray):

```bash
unzip -j libv2ray.aar 'assets/geoip.dat' 'assets/geosite.dat' -d /tmp/geo-src
python3 tools/trim_geo.py /tmp/geo-src app/src/main/assets/geo
```

### Правила администратора (без выпуска новой версии приложения)

Файл `/etc/vpn/app_rules.json` на сервере (пример — `docs/app_rules.example.json`) отдаётся через
`GET /v1/rules`. Приложение забирает его при каждом подключении; новые правила действуют со следующего подключения.

- `proxy_domains` / `proxy_ips` — всегда через VPN (перекрывают российские списки: например, заблокированный `.ru`-сайт);
- `direct_domains` / `direct_ips` — всегда напрямую;
- домены: `example.com` (с поддоменами) или `full:host.example.com` (точное имя);
- IP: адрес или CIDR (`203.0.113.0/24`), префикс `/0` запрещён.

**После каждой правки увеличивайте `version`** — сервер отдаёт правила, только если версия новее, чем у приложения.
Некорректные записи приложение молча отбрасывает (чтобы опечатка не ломала запуск у всех пользователей);
`geosite:`, `geoip:`, `regexp:` в этих правилах не поддерживаются.
