# Wireless ADB QA (Phone USB Busy with HackRF)

Use this when HackRF/PortaPack is connected to phone OTG, so USB ADB cannot be used at the same time.

## One-time setup

1. Enable Developer Options on Android.
1. Enable `USB debugging` and `Wireless debugging`.
1. Connect phone to PC once by USB and trust the computer.

## Switch to wireless ADB

From PC:

```powershell
adb tcpip 5555
adb shell ip -f inet addr show wlan0
```

Find phone Wi-Fi IP (for example `192.168.1.54`), then:

```powershell
adb connect 192.168.1.54:5555
adb devices
```

You should see `<ip>:5555` as `device`.

Now unplug USB from PC and connect phone OTG to HackRF/PortaPack.

## Run automated QA

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\qa_portapack_remote.ps1 -RequireConnected
```

If the script fails on `Connected` check, manually connect inside the app first, then re-run.

If app status shows `Connect failed: USB busy`, stop competing SDR apps and retry:

```powershell
adb shell am force-stop marto.rtl_tcp_andro
adb shell am force-stop com.s33me.myhackrf
```

If needed, unplug/replug OTG after force-stopping those apps.

## Disconnect/reset

```powershell
adb disconnect 192.168.1.54:5555
```
