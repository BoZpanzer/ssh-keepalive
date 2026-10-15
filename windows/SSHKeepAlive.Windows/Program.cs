using System.Diagnostics;
using System.Drawing;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;
using Renci.SshNet;
using Renci.SshNet.Common;
using System.Windows.Forms;

namespace SSHKeepAlive.Windows;

internal sealed class Profile
{
    public string Host { get; set; } = "139.18.143.253";
    public int Port { get; set; } = 22;
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
    public string PinnedHostKey { get; set; } = "";
    public bool Autostart { get; set; } = true;
    public bool SameEndpoint(Profile other) => Host.Equals(other.Host, StringComparison.OrdinalIgnoreCase)
        && Port == other.Port && Username == other.Username;
}

internal sealed class ConnectionStatus
{
    public string State { get; set; } = "Nicht aktiv";
    public string Detail { get; set; } = "Bitte SSH einrichten.";
    public DateTimeOffset Updated { get; set; } = DateTimeOffset.MinValue;
    public int Pid { get; set; }
}

internal static class Storage
{
    internal static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BoZ", "SSHKeepAlive");
    internal static readonly string AppExe = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs", "SSHKeepAlive", "SSHKeepAlive.Windows.exe");
    private static string ProfilePath => Path.Combine(Folder, "profile.dat");
    private static string StatusPath => Path.Combine(Folder, "status.json");
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("BoZ SSHKeepAlive Windows v1");
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    internal static Profile? Load()
    {
        if (!File.Exists(ProfilePath)) return null;
        byte[] decrypted = ProtectedData.Unprotect(File.ReadAllBytes(ProfilePath), Entropy, DataProtectionScope.CurrentUser);
        try { return JsonSerializer.Deserialize<Profile>(decrypted); }
        finally { CryptographicOperations.ZeroMemory(decrypted); }
    }
    internal static void Save(Profile p)
    {
        Directory.CreateDirectory(Folder);
        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(p);
        try
        {
            byte[] encrypted = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
            try
            {
                File.WriteAllBytes(ProfilePath + ".tmp", encrypted);
                File.Move(ProfilePath + ".tmp", ProfilePath, true);
            }
            finally { CryptographicOperations.ZeroMemory(encrypted); }
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }
    internal static void Install()
    {
        var running = Environment.ProcessPath ?? throw new IOException("EXE wurde nicht gefunden.");
        Directory.CreateDirectory(Path.GetDirectoryName(AppExe)!);
        if (!Path.GetFullPath(running).Equals(Path.GetFullPath(AppExe), StringComparison.OrdinalIgnoreCase))
            File.Copy(running, AppExe, overwrite: true);
    }
    internal static void ConfigureAutostart(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
            key!.SetValue("BoZ SSH KeepAlive", $"\"{AppExe}\" --worker", RegistryValueKind.String);
        else
            key?.DeleteValue("BoZ SSH KeepAlive", false);
    }
    internal static void Clear()
    {
        ConfigureAutostart(false);
        if (File.Exists(ProfilePath)) File.Delete(ProfilePath);
    }
    internal static void Status(string state, string detail)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var snapshot = new ConnectionStatus
            {
                State = state, Detail = detail,
                Updated = DateTimeOffset.UtcNow, Pid = Environment.ProcessId
            };
            var tmp = StatusPath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(snapshot));
            File.Move(tmp, StatusPath, true);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
    internal static ConnectionStatus Current()
    {
        try { return JsonSerializer.Deserialize<ConnectionStatus>(File.ReadAllText(StatusPath)) ?? new(); }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { return new(); }
    }
}

internal static class Processes
{
    private const string MutexName = @"Local\BoZ_SSHKeepAlive_OneWorker_1";
    private const string StopEventName = @"Local\BoZ_SSHKeepAlive_Stop_1";
    internal static void Start()
    {
        Process.Start(new ProcessStartInfo(Storage.AppExe, "--worker") {
            UseShellExecute = false, CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(Storage.AppExe)!
        });
    }
    internal static void Stop()
    {
        try { using var e = EventWaitHandle.OpenExisting(StopEventName); e.Set(); }
        catch (WaitHandleCannotBeOpenedException) { }
    }
    internal static int RunWorker()
    {
        using var mutex = new Mutex(true, MutexName, out bool first);
        if (!first) return 0;
        using var stop = new EventWaitHandle(false, EventResetMode.ManualReset, StopEventName);
        stop.Reset();
        Profile? p;
        try { p = Storage.Load(); }
        catch (Exception) { Storage.Status("Fehler", "Zugangsdaten können nicht entschlüsselt werden."); return 2; }
        if (p == null || string.IsNullOrEmpty(p.Password) || string.IsNullOrEmpty(p.PinnedHostKey))
        {
            Storage.Status("Fehler", "Erst im Setup konfigurieren."); return 2;
        }

        int retry = 0;
        while (!stop.WaitOne(0))
        {
            DateTimeOffset? connectedAt = null;
            try
            {
                Storage.Status("Verbinde", $"{p.Host}:{p.Port}, Versuch {retry + 1}");
                using var client = Ssh.Create(p, strict: true, onSeen: null);
                int failed = 0;
                client.ErrorOccurred += (_, _) => Interlocked.Exchange(ref failed, 1);
                client.Connect();
                using var shell = client.CreateShellStream("xterm", 80, 24, 0, 0, 8192);
                connectedAt = DateTimeOffset.UtcNow;
                retry = 0;
                Storage.Status("Verbunden", "SSH-Sitzung aktiv, KeepAlive alle 30 s.");
                var buffer = new byte[4096];
                while (!stop.WaitOne(1000))
                {
                    if (!client.IsConnected || !shell.CanRead || Volatile.Read(ref failed) != 0)
                        throw new IOException("Verbindung abgebrochen.");
                    while (shell.DataAvailable)
                    {
                        int n = shell.Read(buffer, 0, buffer.Length);
                        if (n <= 0) throw new IOException("Sitzung wurde beendet.");
                        CryptographicOperations.ZeroMemory(buffer.AsSpan(0, n));
                    }
                    Storage.Status("Verbunden", "SSH-Sitzung aktiv, KeepAlive alle 30 s.");
                }
            }
            catch (UntrustedHostKeyException)
            {
                Storage.Status("Fehler", "SSH-Serveridentität geändert – im Setup überprüfen."); return 3;
            }
            catch (SshAuthenticationException)
            {
                Storage.Status("Fehler", "Anmeldung fehlgeschlagen – Zugangsdaten überprüfen."); return 4;
            }
            catch (Exception)
            {
                Storage.Status("Getrennt", "Verbindung verloren – Neuversuch folgt.");
            }

            if (stop.WaitOne(0)) break;
            bool stable = connectedAt.HasValue && DateTimeOffset.UtcNow - connectedAt.Value >= TimeSpan.FromMinutes(1);
            if (stable) retry = 0;
            int wait = stable ? 1_000 : (int)Math.Clamp(
                Math.Min(300_000.0, 2_000.0 * Math.Pow(2, Math.Min(retry++, 20))) *
                (0.8 + Random.Shared.NextDouble() * 0.4), 1_000, 360_000);
            Storage.Status("Warte", $"Verbindung wird in etwa {wait / 1000} Sekunden neu versucht.");
            if (stop.WaitOne(wait)) break;
        }
        Storage.Status("Gestoppt", "Vom Nutzer gestoppt.");
        return 0;
    }
}

internal sealed class UntrustedHostKeyException : Exception
{
    internal UntrustedHostKeyException() : base("Serveridentität nicht bestätigt oder geändert.") { }
}

internal sealed record HostIdentity(string Algorithm, string Fingerprint, string PublicKey);

internal static class Ssh
{
    internal static SshClient Create(Profile p, bool strict, Action<HostIdentity>? onSeen)
    {
        var keyboard = new KeyboardInteractiveAuthenticationMethod(p.Username);
        bool answered = false;
        keyboard.AuthenticationPrompt += (_, a) =>
        {
            if (answered) return;
            answered = true;
            foreach (var prompt in a.Prompts)
                if (!prompt.IsEchoed) prompt.Response = p.Password;
        };
        var conn = new ConnectionInfo(p.Host, p.Port, p.Username,
            new PasswordAuthenticationMethod(p.Username, p.Password), keyboard)
        {
            Timeout = TimeSpan.FromSeconds(15)
        };
        var client = new SshClient(conn) { KeepAliveInterval = TimeSpan.FromSeconds(30) };
        client.HostKeyReceived += (_, e) =>
        {
            var fingerprint = "SHA256:" + Convert.ToBase64String(SHA256.HashData(e.HostKey)).TrimEnd('=');
            onSeen?.Invoke(new HostIdentity(e.HostKeyName, fingerprint, Convert.ToBase64String(e.HostKey)));
            byte[] known = Array.Empty<byte>();
            if (!string.IsNullOrEmpty(p.PinnedHostKey))
            {
                try { known = Convert.FromBase64String(p.PinnedHostKey); }
                catch (FormatException) { }
            }
            e.CanTrust = strict && known.Length > 0 &&
                CryptographicOperations.FixedTimeEquals(known, e.HostKey);
        };
        return client;
    }

    internal static HostIdentity Probe(Profile p)
    {
        HostIdentity? seen = null;
        using var client = Create(p, strict: false, onSeen: h => seen = h);
        try { client.Connect(); }
        catch (Exception) when (seen != null) { }
        return seen ?? throw new IOException("SSH-Server nicht erreichbar oder kein Hostschlüssel empfangen.");
    }

    internal static void Test(Profile p)
    {
        using var client = Create(p, strict: true, onSeen: null);
        try
        {
            client.Connect();
            using var shell = client.CreateShellStream("xterm", 80, 24, 0, 0, 8192);
            Thread.Sleep(250);
        }
        catch (SshConnectionException e) when (e.Message.Contains("key", StringComparison.OrdinalIgnoreCase))
        {
            throw new UntrustedHostKeyException();
        }
    }
}

internal sealed class Setup : Form
{
    private readonly TextBox host = new() { Width = 430 };
    private readonly TextBox port = new() { Width = 430 };
    private readonly TextBox username = new() { Width = 430 };
    private readonly TextBox password = new() { Width = 430, UseSystemPasswordChar = true };
    private readonly CheckBox autostart = new() { Text = "Bei Windows-Anmeldung automatisch starten", Width = 430 };
    private readonly Label status = new() { AutoSize = false, Width = 445, Height = 42, Font = new Font("Segoe UI", 12, FontStyle.Bold) };
    private readonly Button start = new() { Text = "START / TEST", Width = 205, Height = 48 };
    private readonly Button stop = new() { Text = "STOP", Width = 205, Height = 48 };
    private readonly Button clear = new() { Text = "Zugangsdaten löschen", Width = 425, Height = 38 };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    private Profile? saved;
    private bool working;

    internal Setup()
    {
        Text = "SSH KeepAlive – Windows";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(500, 660);
        MinimumSize = new Size(500, 660);
        BackColor = Color.FromArgb(18, 30, 46);
        ForeColor = Color.WhiteSmoke;
        Font = new Font("Segoe UI", 10);
        var layout = new FlowLayoutPanel {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false,
            AutoScroll = true, Padding = new Padding(20, 14, 16, 14)
        };
        Controls.Add(layout);
        void AddText(string text, int height = 26, int font = 10)
        {
            layout.Controls.Add(new Label {
                Text = text, Width = 445, Height = height,
                Font = new Font("Segoe UI", font, font >= 18 ? FontStyle.Bold : FontStyle.Regular),
                ForeColor = Color.WhiteSmoke
            });
        }
        void AddField(string label, TextBox field)
        {
            AddText(label, 24);
            layout.Controls.Add(field);
            layout.Controls.Add(new Label { Width = 425, Height = 8 });
        }
        AddText("SSH KeepAlive", 54, 24);
        AddText("Einmal einrichten, dann läuft SSH unsichtbar im Hintergrund.", 44);
        AddField("Server oder IP", host);
        AddField("Port", port);
        AddField("Benutzername", username);
        AddField("Passwort (leer = gespeichertes behalten)", password);
        layout.Controls.Add(autostart);
        var buttons = new FlowLayoutPanel { Width = 445, Height = 60 };
        buttons.Controls.Add(start);
        buttons.Controls.Add(stop);
        layout.Controls.Add(buttons);
        layout.Controls.Add(status);
        layout.Controls.Add(clear);
        AddText("SSH-Fingerprint beim ersten Start unabhängig prüfen. " +
            "Der Worker läuft während Windows angemeldet und wach ist. " +
            "Kein SSH-KeepAlive während Standby oder Herunterfahren.", 75);

        try { saved = Storage.Load(); }
        catch (Exception) { MessageBox.Show("Gespeicherte Daten konnten nicht entschlüsselt werden."); }
        var p = saved ?? new Profile();
        host.Text = p.Host;
        port.Text = p.Port.ToString();
        username.Text = p.Username;
        autostart.Checked = p.Autostart;
        start.Click += async (_, _) => await StartAsync();
        stop.Click += (_, _) => Processes.Stop();
        clear.Click += (_, _) => ClearProfile();
        timer.Tick += (_, _) => RefreshStatus();
        timer.Start();
        RefreshStatus();
    }

    private Profile Validate()
    {
        string h = host.Text.Trim();
        if (string.IsNullOrWhiteSpace(h) || Uri.CheckHostName(h) == UriHostNameType.Unknown)
            throw new ArgumentException("Ungültige Serveradresse.");
        if (!int.TryParse(port.Text, out int n) || n < 1 || n > 65535)
            throw new ArgumentException("Port muss zwischen 1 und 65535 liegen.");
        if (string.IsNullOrWhiteSpace(username.Text)) throw new ArgumentException("Benutzername fehlt.");
        var p = new Profile { Host = h, Port = n, Username = username.Text.Trim(),
            Autostart = autostart.Checked };
        if (saved != null && p.SameEndpoint(saved))
        {
            p.Password = saved.Password; p.PinnedHostKey = saved.PinnedHostKey;
        }
        if (!string.IsNullOrEmpty(password.Text)) p.Password = password.Text;
        if (string.IsNullOrWhiteSpace(p.Password)) throw new ArgumentException("SSH-Passwort fehlt.");
        return p;
    }
    private async Task StartAsync()
    {
        if (working || IsRunning()) return;
        working = true;
        start.Enabled = false;
        try
        {
            var p = Validate();
            status.Text = "Prüfe SSH-Serveridentität …";
            var identity = await Task.Run(() => Ssh.Probe(p));
            bool known = !string.IsNullOrEmpty(p.PinnedHostKey);
            bool match = known && CryptographicOperations.FixedTimeEquals(
                Convert.FromBase64String(p.PinnedHostKey), Convert.FromBase64String(identity.PublicKey));
            if (!match)
            {
                string warning = known
                    ? "WARNUNG: Der SSH-Hostschlüssel hat sich geändert!\nNur nach unabhängiger Prüfung neu akzeptieren.\n\n"
                    : "Erster Kontakt. Bitte SSH-Fingerprint beim Betreiber unabhängig überprüfen.\n\n";
                if (MessageBox.Show(this, $"{warning}Server: {p.Host}:{p.Port}\n" +
                        $"Typ: {identity.Algorithm}\nFingerprint: {identity.Fingerprint}\n\nSchlüssel vertrauen?",
                        "SSH-Serveridentität", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                        MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;
                p.PinnedHostKey = identity.PublicKey;
            }
            status.Text = "Teste SSH-Anmeldung und Shell …";
            await Task.Run(() => Ssh.Test(p));
            Storage.Install();
            Storage.Save(p);
            Storage.ConfigureAutostart(p.Autostart);
            saved = p;
            password.Clear();
            Processes.Start();
            status.Text = "Worker wurde gestartet. Fenster kann geschlossen werden.";
        }
        catch (Exception e) { MessageBox.Show(this, e.Message, "SSH-Verbindung fehlgeschlagen",
            MessageBoxButtons.OK, MessageBoxIcon.Error); }
        finally { working = false; RefreshStatus(); }
    }
    private bool IsRunning()
    {
        var s = Storage.Current();
        return DateTimeOffset.UtcNow - s.Updated < TimeSpan.FromSeconds(15) &&
            s.State is "Verbinden" or "Verbunden" or "Warte" or "Getrennt";
    }
    private void RefreshStatus()
    {
        if (working) return;
        var s = Storage.Current();
        bool active = IsRunning();
        status.Text = active ? $"{s.State} – {s.Detail}" : s.State == "Fehler"
            ? $"Fehler – {s.Detail}" : "Nicht aktiv – bereit zum Starten";
        start.Enabled = !active;
        stop.Enabled = active;
        clear.Enabled = !active;
        host.Enabled = port.Enabled = username.Enabled = password.Enabled = autostart.Enabled = !active;
    }
    private void ClearProfile()
    {
        if (IsRunning()) return;
        if (MessageBox.Show(this, "Gespeicherte Zugangsdaten löschen?", "Löschen",
            MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        Storage.Clear();
        saved = null;
        username.Clear();
        password.Clear();
        autostart.Checked = false;
        RefreshStatus();
    }
}

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--worker", StringComparer.OrdinalIgnoreCase)) return Processes.RunWorker();
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                var p = new Profile();
                return p.Port == 22 && p.Host.Length > 0 ? 0 : 1;
            }
            catch { return 1; }
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new Setup());
        return 0;
    }
}
