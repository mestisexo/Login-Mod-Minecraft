# LoginMod: guía de comandos y respaldos

Mod de login para servidores **Minecraft 1.21.1 + NeoForge 21.1.259** en `online-mode=false`.

---

## 1. Comandos para jugadores

| Comando | Para qué sirve |
|---|---|
| `/register <clave> <clave>` | Crea la cuenta la primera vez. La clave se escribe dos veces para confirmar. |
| `/login <clave>` | Inicia sesión en entradas posteriores. |
| `/changepassword <actual> <nueva>` | Cambia la contraseña (solo si ya iniciaste sesión). |

**Reglas**
- La clave solo admite letras, números y los símbolos `_ - . +` (sin espacios).
- Largo mínimo por defecto: 6 caracteres.
- Antes de autenticarse el jugador no puede moverse, chatear, romper/poner bloques, interactuar, tirar o recoger ítems, ni usar otros comandos.

## 2. Comandos para administradores (OP nivel 3 o más)

| Comando | Para qué sirve |
|---|---|
| `/loginadmin reset <nombre>` | Borra la cuenta de un jugador. Al volver deberá usar `/register`. |
| `/loginadmin setpass <nombre> <clave>` | Cambia la contraseña de un jugador sin dejar la cuenta libre. **Recomendado** cuando alguien olvida su clave. |

Notas
- La consola del servidor siempre puede usar `/loginadmin`.
- Con `reset`, mientras la cuenta está libre cualquiera podría registrarse con ese nombre. Prefiere `setpass`.

## 3. Comandos útiles de Minecraft (administración)

| Comando | Para qué sirve |
|---|---|
| `op <nombre>` / `deop <nombre>` | Da o quita permisos de administrador. |
| `whitelist add <nombre>` | Permite entrar a un jugador. |
| `whitelist remove <nombre>` | Quita a un jugador de la whitelist. |
| `kick <nombre>` | Expulsa a un jugador. |
| `ban <nombre>` / `pardon <nombre>` | Banea o perdona. |
| `save-all` | Guarda el mundo en disco. |
| `stop` | Apaga el servidor guardando todo. |
| `/tick query` | Muestra el tiempo promedio por tick (rendimiento). |
| `perf start` / `perf stop` | Genera un informe de rendimiento. |

## 4. Niveles de OP

| Nivel | Qué permite |
|---|---|
| 1 | Modificar el área protegida del spawn. |
| 2 | Comandos de juego (`/gamemode`, `/give`, `/tp`...). **No** puede usar `/loginadmin`. |
| 3 | Administrar jugadores (`/kick`, `/ban`, `/whitelist`) y `/loginadmin`. |
| 4 | Control del servidor (`/stop`, `/save-all`). |

Nivel por defecto: `op-permission-level` en `server.properties`.
Nivel por jugador: editar `ops.json` **con el servidor apagado**.

---

## 5. Configuración (`config/loginmod-common.toml`)

| Opción | Por defecto | Qué hace |
|---|---|---|
| `loginTimeoutSeconds` | 60 | Segundos para iniciar sesión antes de ser expulsado. |
| `maxAttempts` | 3 | Fallos por sesión antes de expulsar. |
| `minPasswordLength` | 6 | Largo mínimo de contraseña. |
| `ipMaxFails` | 6 | Fallos desde una IP antes de bloquearla. |
| `ipLockSeconds` | 300 | Duración del bloqueo de IP. |

Los cambios se aplican al reiniciar el servidor.

---

## 6. Archivos importantes

| Archivo / carpeta | Contenido |
|---|---|
| `world/` | El mundo (nombre según `level-name` en `server.properties`). |
| `mods/` | Los `.jar` de los mods. |
| `config/loginmod-users.json` | Cuentas de login (hashes, sin texto plano). **Crítico.** |
| `config/loginmod-common.toml` | Ajustes del mod. |
| `config/` (resto) | Configuración de otros mods. |
| `server.properties`, `ops.json`, `whitelist.json` | Configuración y permisos del servidor. |
| `banned-players.json`, `banned-ips.json` | Baneos. |

---

## 7. Respaldos (backups)

### Qué respaldar
1. **Mundo:** carpeta `world/`.
2. **Cuentas de login:** `config/loginmod-users.json`. Si se pierde, todos deben registrarse de nuevo y alguien podría adueñarse de un nombre.
3. **Configuración:** carpeta `config/`, `server.properties`, `ops.json`, `whitelist.json`.
4. **Mods:** carpeta `mods/` (o al menos la lista de versiones exactas).
5. **Código fuente del mod:** el proyecto de IntelliJ.

### Regla 3-2-1
- **3** copias de tus datos,
- en **2** tipos de almacenamiento distintos (por ejemplo disco del PC + disco externo),
- **1** copia fuera de casa (nube o servidor remoto).

### Cómo copiar el mundo sin corromperlo
Con el servidor encendido, desde la consola:

```
save-off
save-all flush
```

Copia la carpeta del servidor y después:

```
save-on
```

La opción más simple y segura: **apagar con `stop`**, copiar y volver a encender.

### Automatizar en Windows (script `.bat`)
Guarda esto como `backup.bat` y prográmalo en el *Programador de tareas* de Windows (diario, con el servidor apagado o con un plugin de comandos de consola):

```bat
@echo off
set ORIGEN=C:\servidor
set DESTINO=D:\backups\servidor
set FECHA=%date:~-4%-%date:~3,2%-%date:~0,2%_%time:~0,2%%time:~3,2%
set FECHA=%FECHA: =0%

robocopy "%ORIGEN%" "%DESTINO%\%FECHA%" /E /XD logs crash-reports /R:1 /W:1
```

Ajusta `ORIGEN` y `DESTINO`. Esto **no** pausa el guardado: úsalo con el servidor apagado, o ejecuta antes `save-off` y `save-all flush`.

### Frecuencia y retención sugeridas
- **Diario**, conservando los últimos 7.
- **Semanal**, conservando los últimos 4.
- **Antes de cambios grandes** (actualizar mods, cambiar de versión, editar `ops.json`): copia manual.
- Borra copias viejas para no llenar el disco. Un mundo comprimido pesa mucho menos (`.zip` o `.7z`).

### Código del mod: usa Git
En la carpeta del proyecto:

```
git init
git add .
git commit -m "LoginMod funcionando"
```

Crea un archivo `.gitignore` con:

```
build/
.gradle/
run/
.idea/
*.iml
```

Sube el repositorio a **GitHub o GitLab en modo privado**, así tienes copia fuera del PC. Haz un commit cada vez que algo funcione.

Además, guarda cada `.jar` que despliegues con su fecha, por ejemplo `loginmod-1.0-2026-10-17.jar`, para poder volver atrás.

### Seguridad de los respaldos
- `loginmod-users.json` contiene hashes de contraseñas. Aunque estén protegidos con sal y 120.000 iteraciones, **no lo subas a repositorios públicos ni lo compartas**.
- Guarda los respaldos en nube con contraseña o cifrados (por ejemplo `.7z` con clave).
- Restringe quién tiene acceso a la carpeta del servidor y al panel.
- **Prueba restaurar** un respaldo de vez en cuando en otra carpeta. Un backup que nunca se probó puede no servir.

### Lista de verificación semanal
- [ ] Existe un respaldo reciente del mundo.
- [ ] `loginmod-users.json` incluido en el respaldo.
- [ ] Hay una copia fuera del PC.
- [ ] El código del mod está subido a Git.
- [ ] Probé abrir un respaldo al menos una vez este mes.

---

## 8. Instalación en el servidor real

1. Ejecuta `build` en Gradle y toma el `.jar` de `build/libs/`.
2. Copia el `.jar` a `mods/` del servidor real.
3. Verifica en `server.properties`: `online-mode=false` y `white-list=true`.
4. **Registra primero tu cuenta de admin** y luego abre el servidor al público.
5. Revisa el log al arrancar: debe aparecer `LoginMod cargado` y `filtro de logs instalado`.
6. Verifica que `/login` no aparezca en `logs/latest.log`.
