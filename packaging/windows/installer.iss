; Omarchy Music for Windows: installs dist\OmarchyMusic (made by build.sh) for the
; current user, with no administrator rights needed, and adds it to the Start menu.
; Build: ISCC /DAppVersion=1.0.0 packaging\windows\installer.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{8C1F3B52-6D0E-4C4B-9E27-0F6A7B3D9C11}
AppName=Omarchy Music
AppVersion={#AppVersion}
AppVerName=Omarchy Music {#AppVersion}
AppPublisher=Xavier Reid
AppPublisherURL=https://github.com/Varixxl/omarchy-music
DefaultDirName={localappdata}\Programs\Omarchy Music
DefaultGroupName=Omarchy Music
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\dist
OutputBaseFilename=OmarchyMusic-Setup
SetupIconFile=omarchy-music.ico
UninstallDisplayIcon={app}\omarchy-music.ico
UninstallDisplayName=Omarchy Music
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
LicenseFile=..\..\LICENSE
; Write a log to %TEMP% ("Setup Log <date> #<n>.txt") so a failed install says why.
SetupLogging=yes
; An update runs while the old copy may still be closing: close whatever still holds its
; files instead of failing on the first file in use (which, with /SUPPRESSMSGBOXES, aborts
; and rolls the whole update back). The app is reopened by [Run] below, not by Windows.
CloseApplications=force
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Add a shortcut to the desktop"; Flags: unchecked

[Files]
Source: "..\..\dist\OmarchyMusic\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[InstallDelete]
; An update replaces the old copy whole (no stale Python modules or DLLs left over).
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\lib"
Type: filesandordirs; Name: "{app}\share"

[Icons]
Name: "{autoprograms}\Omarchy Music"; Filename: "{app}\bin\pythonw.exe"; Parameters: """{app}\omarchy-music"""; WorkingDir: "{app}"; IconFilename: "{app}\omarchy-music.ico"; AppUserModelID: "Omarchy.Music"; Comment: "Music from YouTube Music, Tidal, Apple Music and Spotify, in FLAC"
Name: "{autodesktop}\Omarchy Music"; Filename: "{app}\bin\pythonw.exe"; Parameters: """{app}\omarchy-music"""; WorkingDir: "{app}"; IconFilename: "{app}\omarchy-music.ico"; AppUserModelID: "Omarchy.Music"; Tasks: desktopicon

[Run]
Filename: "{app}\bin\pythonw.exe"; Parameters: """{app}\omarchy-music"""; WorkingDir: "{app}"; Description: "Start Omarchy Music"; Flags: nowait postinstall skipifsilent
; An update started from the app (Setup.exe /SILENT /UPDATE=1) opens the new copy when it's done.
Filename: "{app}\bin\pythonw.exe"; Parameters: """{app}\omarchy-music"""; WorkingDir: "{app}"; Flags: nowait; Check: IsUpdate

[Code]
function IsUpdate: Boolean;
begin
  Result := ExpandConstant('{param:UPDATE|0}') = '1';
end;

{ Before any file is touched: wait for the programs running from this install (the app's
  pythonw.exe, mpv.exe, ffmpeg.exe...) to quit, and end the ones that don't within 20 s.
  The app quits a moment after starting the update, but saving its state and stopping mpv
  can take longer than that, and a file still in use made the update roll back. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Dir, Quoted, Match, Script: String;
  Code: Integer;
begin
  Result := '';
  Dir := ExpandConstant('{app}');
  if not DirExists(Dir) then
    exit;
  Quoted := AddBackslash(Dir);
  StringChangeEx(Quoted, '''', '''''', True);   { a ' in the folder's name, doubled for PowerShell }
  Match := '$d = ''' + Quoted + '''; ' +
           'function Mine { Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($d, [StringComparison]::OrdinalIgnoreCase) } }; ';
  Script := Match +
            '$p = Mine; if ($p) { $p | Wait-Process -Timeout 20 -ErrorAction SilentlyContinue }; ' +
            '$p = Mine; if ($p) { $p | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1 }';
  Log('Waiting for programs running from ' + Dir + ' to quit');
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
              '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + Script + '"',
              '', SW_HIDE, ewWaitUntilTerminated, Code) then
    Log('Could not run PowerShell to wait for the old copy: ' + SysErrorMessage(Code));
end;
