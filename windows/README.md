# SSH KeepAlive – Windows

A standalone Windows x64 app with a setup screen and a headless SSH worker.

## Operation
1. Run `SSH-KeepAlive.exe`.
2. Enter SSH host, port, username and password, then select **Start & Test**.
3. Check the presented SSH host-key SHA256 fingerprint against a trusted source before accepting it.
4. After the connection test, the application launches a silent background worker. Closing the setup window does not stop it.
5. Reopen the EXE to see status, update credentials, or click **Stop**.

The worker opens an SSH shell and does not send remote commands. Server output is read and discarded. It sends keepalives every 30s, reconnects on disconnection, and has exponential backoff. Passwords and pinned SSH host keys are saved via Windows DPAPI (current Windows account). Autostart at user logon is optional. The worker is not a Windows Service and will stop when Windows sleeps, shuts down, or the user logs out.

## Build
`dotnet publish windows/SSHKeepAlive.Windows/SSHKeepAlive.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist`

The GitHub Actions workflow creates a downloadable EXE artifact on pushes and publishes an EXE under GitHub Releases after merging to main. Builds have not been tested against live StudNET; use only on systems and accounts you are authorized to access.

## Security
Do not commit real passwords. There is no remote control, telemetry, or unverified automatic host-key acceptance. The executable is currently unsigned, so Windows SmartScreen may warn.