param(
  [string]$TitleB64 = "",
  [string]$BodyB64 = ""
)

# The title and the body travel as base64 of UTF-8: the command line is
# cmd.exe, which mangles anything that is not plain ASCII.
function ConvertFrom-B64([string]$value) {
  if (-not $value) { return "" }
  try {
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
  } catch {
    return ""
  }
}

$title = ConvertFrom-B64 $TitleB64
$body = ConvertFrom-B64 $BodyB64
if (-not $title) { $title = "minis" }

# A toast belongs to the shell, not to this process: it stays on screen and
# in the notification centre after the script has gone.
try {
  [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
  $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
  $nodes = $template.GetElementsByTagName("text")
  $nodes.Item(0).AppendChild($template.CreateTextNode($title)) | Out-Null
  $nodes.Item(1).AppendChild($template.CreateTextNode($body)) | Out-Null
  $toast = [Windows.UI.Notifications.ToastNotification]::new($template)
  [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("Minis Hub").Show($toast)
  exit 0
} catch {
  # No toast on this machine: fall through to the tray balloon.
}

# A balloon needs the process that owns it to stay alive, which is why the
# caller starts this script detached.
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$icon = New-Object System.Windows.Forms.NotifyIcon
$icon.Icon = [System.Drawing.SystemIcons]::Information
$icon.BalloonTipTitle = $title
$icon.BalloonTipText = $body
$icon.Visible = $true
$icon.ShowBalloonTip(20000)
Start-Sleep -Seconds 20
$icon.Dispose()
