package org.openexam.signalwatch

/**
 * Conservative, offline name-based triage for scan RESULTS (not confirmed devices).
 * Never infer a cheating device from RSSI or a random MAC address.
 * Only a string that actually identifies a class of devices is used as evidence.
 */
enum class DeviceCategory(val display: String) {
    SUSPECT("의심 기기"), PHONE("스마트폰·핫스팟"), HOME("일반 가전·설비"), UNKNOWN("분류 불명")
}

data class DeviceGuess(
    val category: DeviceCategory,
    val guessedType: String,
    val evidence: String,
    val confidence: String
)

object DeviceClassifier {
    private val smartGlasses = Regex("""(?i)(smart[ _-]?glasses?|ai[ _-]?glasses?|ray[ _-]?ban[ _-]?meta|meta[ _-]?glasses?|spectacles|스마트안경|스마트글라스)""")
    private val camera = Regex("""(?i)(hidden[ _-]?cam|spy[ _-]?cam|mini[ _-]?cam|wireless[ _-]?cam|camera|webcam|ip[ _-]?cam|초소형카메라|무선카메라)""")
    private val earphone = Regex("""(?i)(airpods|galaxy[ _-]?buds|ear[ _-]?buds?|ear[ _-]?phones?|head[ _-]?phones?|headset|\bTWS\b|이어폰|이어버드)""")
    private val watch = Regex("""(?i)(smart[ _-]?watch|\bwatch\b|galaxy[ _-]?fit|스마트워치|시계)""")
    private val phone = Regex("""(?i)(iphone|\bgalaxy[ _-]?(?:s[0-9]{1,2}|z[ _-]?(?:flip|fold)|a[0-9]{2}|note[0-9]{1,2})|\bpixel[ _-]?[0-9]{1,2}|redmi[ _-]?(?:note|[0-9]{1,2}\b)|\b(?:androidap|iphone[ _-]?hotspot|phone[ _-]?hotspot|mobile[ _-]?hotspot)\b|갤럭시|아이폰|휴대폰)""")
    private val home = Regex("""(?i)(fridge|refrigerator|washer|washing[ _-]?machine|air[ _-]?purifier|airpurifier|floor[ _-]?a/c|air[ _-]?conditioner|robot[ _-]?vacuum|dryer|tv[ _-]?speaker|giga[ _-]?genie|냉장고|세탁기|공기청정기|에어컨|청소기)""")
    private val routers = Regex("""(?i)(^KT_GiGA|^U\+Net|^SK_WiFi|^SK_[A-F0-9]{4}|^LGWiFi|^5G_LGWiFi|iptime|^HelloWireless|^\w*router|^\w*mesh|\baccess[ _-]?point\b|\bhotspot[ _-]?router\b)""")

    fun classify(kind: SignalKind, visibleName: String): DeviceGuess {
        val name = visibleName.trim().take(75)
        if (name.isBlank() || name == "이름 미표시 BLE 신호" || name == "이름 숨김 접속점") {
            return DeviceGuess(DeviceCategory.UNKNOWN, "종류 확인 불가", "광고 이름 또는 SSID 정보가 없습니다", "판별 불가")
        }
        if (smartGlasses.containsMatchIn(name)) return match(DeviceCategory.SUSPECT, "스마트안경 추정", name)
        if (camera.containsMatchIn(name)) return match(DeviceCategory.SUSPECT, "카메라 추정", name)
        if (earphone.containsMatchIn(name)) return match(DeviceCategory.SUSPECT, "이어폰·오디오 장치 추정", name)
        if (watch.containsMatchIn(name)) return match(DeviceCategory.SUSPECT, "스마트워치·착용형 장치 추정", name)
        if (phone.containsMatchIn(name)) return match(DeviceCategory.PHONE, if (kind == SignalKind.WIFI) "휴대전화 핫스팟 추정" else "스마트폰 추정", name)
        if (home.containsMatchIn(name)) return match(DeviceCategory.HOME, "가전·생활기기 추정", name)
        if (kind == SignalKind.WIFI && routers.containsMatchIn(name)) return match(DeviceCategory.HOME, "공유기·와이파이 설비 추정", name)
        return DeviceGuess(DeviceCategory.UNKNOWN, "종류 확인 불가", "이름 '$name'만으로 기기 종류를 판단하기 어렵습니다", "판별 불가")
    }

    private fun match(category: DeviceCategory, type: String, name: String) = DeviceGuess(
        category, type, "송출 이름 '$name'에 포함된 단어로 추정했습니다. 기종을 확인한 결과는 아닙니다.", "이름 기반 추정"
    )
}
