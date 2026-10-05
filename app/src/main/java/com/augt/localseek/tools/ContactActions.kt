package com.augt.localseek.tools

enum class ContactActionKind(val label: String, val packageName: String?) {
    CALL("Call", null),
    SMS("SMS", null),
    WHATSAPP("WhatsApp", "com.whatsapp"),
    TELEGRAM("Telegram", "org.telegram.messenger"),
    SIGNAL("Signal", "org.thoughtcrime.securesms"),
    EMAIL("Email", null)
}

/** [intentAction] is an android.intent.action.* string so this class stays free of Android types. */
data class ContactAction(
    val kind: ContactActionKind,
    val intentAction: String,
    val uri: String,
    val packageName: String?
) {
    val label: String get() = kind.label
}

object ContactActions {

    private const val ACTION_DIAL = "android.intent.action.DIAL"
    private const val ACTION_SENDTO = "android.intent.action.SENDTO"
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Keeps digits and a single leading '+'; returns null if fewer than 3 digits remain. */
    fun normalizePhone(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 3) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Call (ACTION_DIAL, so no CALL_PHONE permission) and SMS are always offered. The messenger deep links are
     * offered only for international numbers (leading '+', which those apps need to find the chat) and only when
     * the app's package is in [installedPackages].
     */
    fun build(rawPhone: String?, installedPackages: Set<String>, email: String? = null): List<ContactAction> {
        val phone = rawPhone?.let { normalizePhone(it) }
        val actions = mutableListOf<ContactAction>()
        if (phone != null) {
            actions += ContactAction(ContactActionKind.CALL, ACTION_DIAL, "tel:$phone", null)
            actions += ContactAction(ContactActionKind.SMS, ACTION_SENDTO, "smsto:$phone", null)
        }
        if (phone != null && phone.startsWith("+")) {
            val digits = phone.drop(1)
            fun addIfInstalled(kind: ContactActionKind, uri: String) {
                if (kind.packageName in installedPackages) {
                    actions += ContactAction(kind, ACTION_VIEW, uri, kind.packageName)
                }
            }
            addIfInstalled(ContactActionKind.WHATSAPP, "https://wa.me/$digits")
            addIfInstalled(ContactActionKind.TELEGRAM, "tg://resolve?phone=$digits")
            addIfInstalled(ContactActionKind.SIGNAL, "https://signal.me/#p/$phone")
        }
        val mail = email?.trim()?.takeIf { it.contains('@') && it.none { c -> c.isWhitespace() } }
        if (mail != null) actions += ContactAction(ContactActionKind.EMAIL, ACTION_SENDTO, "mailto:$mail", null)
        return actions
    }

    /** Packages that need a `<queries>` entry in the manifest so [installedPackages] can see them. */
    val messengerPackages: List<String> =
        ContactActionKind.values().mapNotNull { it.packageName }
}
