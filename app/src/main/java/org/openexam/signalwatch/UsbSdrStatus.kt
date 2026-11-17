package org.openexam.signalwatch

import android.content.Context
import android.hardware.usb.UsbManager

/** Only identifies likely RTL2832 USB dongles. NO IQ samples are read in v0.1. */
object UsbSdrStatus {
    fun getDescription(context: Context): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return "RTL-SDR · USB 지원 확인 불가 / SDR 수신 미구현"
        val matches = usbManager.deviceList.values.filter { device ->
            val vendor = device.vendorId
            val product = device.productId
            (vendor == 0x0bda && (product == 0x2832 || product == 0x2838))
        }
        return if (matches.isNotEmpty()) {
            "RTL-SDR 추정 USB 장치 발견 · RF 수신 기능 미구현"
        } else {
            "RTL-SDR 미연결 · RF 수신 기능 미구현"
        }
    }
}
