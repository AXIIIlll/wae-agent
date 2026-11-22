@echo off
REM Disable VBS / VirtualMachinePlatform / Hyper-V launch so VirtualBox gets native VT-x.
REM HOW TO USE: right-click this file -> "Run as administrator"
set LOG=E:\WAE\emulator\fix_hyperv.log
echo === %DATE% %TIME% === > "%LOG%"
echo [1/4] bcdedit hypervisorlaunchtype off>> "%LOG%"
bcdedit /set hypervisorlaunchtype off >> "%LOG%" 2>&1
echo [2/4] dism disable VirtualMachinePlatform>> "%LOG%"
dism /online /disable-feature /featurename:VirtualMachinePlatform /norestart >> "%LOG%" 2>&1
echo [3/4] current BCD>> "%LOG%"
bcdedit /enum "{current}" >> "%LOG%" 2>&1
echo.
echo ============================================
echo  DONE. Check for: hypervisorlaunchtype Off
echo  Rebooting in 8 seconds...
echo ============================================
findstr /C:"hypervisorlaunchtype" "%LOG%"
timeout /t 8
shutdown /r /t 0
