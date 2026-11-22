@echo off
REM Enable Windows Hypervisor Platform (WHPX API) so QEMU WHPX and VirtualBox NEM can work.
REM HOW TO USE: right-click this file -> "Run as administrator"
set LOG=E:\WAE\emulator\fix_whpx.log
echo === %DATE% %TIME% === > "%LOG%"
echo [1/3] enable HypervisorPlatform>> "%LOG%"
dism /online /enable-feature /featurename:HypervisorPlatform /all /norestart >> "%LOG%" 2>&1
echo [2/3] bcdedit hypervisorlaunchtype auto>> "%LOG%"
bcdedit /set hypervisorlaunchtype auto >> "%LOG%" 2>&1
echo [3/3] verify>> "%LOG%"
dism /online /get-featureinfo /featurename:HypervisorPlatform >> "%LOG%" 2>&1
echo.
echo ============================================
echo  DONE. Rebooting in 8 seconds...
echo ============================================
findstr /I "State\|??\|????" "%LOG%"
timeout /t 8
shutdown /r /t 0
