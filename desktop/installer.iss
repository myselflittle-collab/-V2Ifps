; Inno Setup script: wraps the PyInstaller folder into FrameX-Setup-<version>.exe
; Installs per user (no administrator rights needed).
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6B8F3B7A-4C2D-4E7A-9F5B-2A1D9C3E7F10}
AppName=FrameX
AppVersion={#AppVersion}
AppVerName=FrameX {#AppVersion}
AppPublisher=Purnananda Mishra
AppPublisherURL=https://github.com/myselflittle-collab/-V2Ifps
DefaultDirName={localappdata}\Programs\FrameX
DefaultGroupName=FrameX
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist_installer
OutputBaseFilename=FrameX-Setup-{#AppVersion}
SetupIconFile=assets\framex.ico
UninstallDisplayIcon={app}\FrameX.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\dist\FrameX\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\FrameX"; Filename: "{app}\FrameX.exe"
Name: "{group}\Uninstall FrameX"; Filename: "{uninstallexe}"
Name: "{userdesktop}\FrameX"; Filename: "{app}\FrameX.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\FrameX.exe"; Description: "Open FrameX now"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\FrameX\workspace"
