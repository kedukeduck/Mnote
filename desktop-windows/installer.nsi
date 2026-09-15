Unicode True
Target amd64-unicode
!include "MUI2.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"
!ifndef PAYLOAD
  !error "Pass /DPAYLOAD=absolute payload directory"
!endif
!ifndef OUTPUT
  !error "Pass /DOUTPUT=absolute installer path"
!endif
Name "Mnote 1.8.0-test"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\Mnote"
InstallDirRegKey HKCU "Software\Mnote" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "Mnote · 你的个人知识库"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\mnote.exe"
!define MUI_FINISHPAGE_RUN_TEXT "打开 Mnote"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"
VIProductVersion "1.8.0.0"
VIAddVersionKey "ProductName" "Mnote"
VIAddVersionKey "FileDescription" "Mnote Windows x64 Installer"
VIAddVersionKey "FileVersion" "1.8.0-test"
VIAddVersionKey "LegalCopyright" "Mnote contributors"

Function .onInit
  ${GetParameters} $1
  ClearErrors
  ${GetOptions} $1 "/UPDATE" $2
  IfErrors notUpdating
  ClearErrors
  ${GetOptions} $1 "/UPDATEPID=" $2
  IfErrors waitForWindow
  System::Call 'kernel32::OpenProcess(i 0x00100000, i 0, i r2) p.r4'
  StrCmp $4 0 waitForWindow
  System::Call 'kernel32::WaitForSingleObject(p r4, i 30000) i.r5'
  System::Call 'kernel32::CloseHandle(p r4)'
  StrCmp $5 0 waitForWindow
  MessageBox MB_OK|MB_ICONINFORMATION "Mnote 仍在结束同步或保存，请稍后从旧版重新安装更新。未强制退出应用。" /SD IDOK
  Abort
  waitForWindow:
  StrCpy $3 0
  waitForOldVersion:
    FindWindow $0 "PersonalCapture.MessageWindow" ""
    StrCmp $0 0 notUpdating
    IntOp $3 $3 + 1
    IntCmp $3 300 notUpdating notYet notUpdating
    notYet:
      Sleep 100
      Goto waitForOldVersion
  notUpdating:
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "此版本需要 64 位 Windows 10 / 11。"
    Abort
  ${EndIf}
  checkRunning:
    FindWindow $0 "PersonalCapture.MessageWindow" ""
    StrCmp $0 0 ready
    IfSilent cannotInstall
    MessageBox MB_RETRYCANCEL|MB_ICONINFORMATION "请先保存内容，并从 Mnote 托盘菜单退出应用，然后点击重试。安装不会删除你的记录。" IDRETRY checkRunning
    cannotInstall:
      Abort
    ready:
FunctionEnd

Section "Mnote" Main
  SetOutPath "$INSTDIR"
  File "${PAYLOAD}/mnote.exe"
  File "${PAYLOAD}/README.md"
  File "${PAYLOAD}/THIRD-PARTY-NOTICES.txt"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Mnote"
  CreateShortcut "$SMPROGRAMS\Mnote\Mnote.lnk" "$INSTDIR\mnote.exe"
  CreateShortcut "$DESKTOP\Mnote.lnk" "$INSTDIR\mnote.exe"
  WriteRegStr HKCU "Software\Mnote" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "DisplayName" "Mnote"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "DisplayVersion" "1.8.0-test"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "Publisher" "Mnote"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "DisplayIcon" "$INSTDIR\mnote.exe"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote" "NoRepair" 1
SectionEnd

Function un.onInit
  checkRunning:
    FindWindow $0 "PersonalCapture.MessageWindow" ""
    StrCmp $0 0 ready
    IfSilent cannotUninstall
    MessageBox MB_RETRYCANCEL|MB_ICONINFORMATION "请先保存内容并退出 Mnote，然后重试。个人记录将保留。" IDRETRY checkRunning
    cannotUninstall:
      Abort
    ready:
FunctionEnd

Section "Uninstall"
  Delete "$INSTDIR\mnote.exe"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\THIRD-PARTY-NOTICES.txt"
  Delete "$INSTDIR\Uninstall.exe"
  Delete "$DESKTOP\Mnote.lnk"
  Delete "$SMPROGRAMS\Mnote\Mnote.lnk"
  RMDir "$SMPROGRAMS\Mnote"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Mnote"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Mnote"
  ; Never remove %LOCALAPPDATA%\PersonalCapture, sessions, records, Inbox or assets.
SectionEnd
