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
