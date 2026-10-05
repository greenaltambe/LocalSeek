package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactActionsTest {

    private val allInstalled = ContactActions.messengerPackages.toSet()

    @Test fun normalizesPhoneNumbers() {
        assertEquals("+919876543210", ContactActions.normalizePhone("+91 98765-43210"))
        assertEquals("5551234", ContactActions.normalizePhone("(555) 1234"))
        assertEquals("+15551234567", ContactActions.normalizePhone(" +1 (555) 123-4567 "))
        assertNull(ContactActions.normalizePhone("12"))
        assertNull(ContactActions.normalizePhone("abc"))
        assertNull(ContactActions.normalizePhone(""))
    }

    @Test fun callAndSmsUseDialAndSendtoWithoutAPackage() {
        val a = ContactActions.build("+1 555 123 4567", emptySet())
        assertEquals(listOf(ContactActionKind.CALL, ContactActionKind.SMS), a.map { it.kind })
        assertEquals("android.intent.action.DIAL", a[0].intentAction)
        assertEquals("tel:+15551234567", a[0].uri)
        assertEquals("android.intent.action.SENDTO", a[1].intentAction)
        assertEquals("smsto:+15551234567", a[1].uri)
        assertTrue(a.all { it.packageName == null })
    }

    @Test fun messengerLinksOnlyWhenInstalledAndInternational() {
        val all = ContactActions.build("+44 20 7946 0958", allInstalled)
        assertEquals(
            listOf(
                ContactActionKind.CALL, ContactActionKind.SMS, ContactActionKind.WHATSAPP,
                ContactActionKind.TELEGRAM, ContactActionKind.SIGNAL
            ),
            all.map { it.kind }
        )
        assertEquals("https://wa.me/442079460958", all[2].uri)
        assertEquals("com.whatsapp", all[2].packageName)
        assertEquals("tg://resolve?phone=442079460958", all[3].uri)
        assertEquals("https://signal.me/#p/+442079460958", all[4].uri)

        val onlyTelegram = ContactActions.build("+442079460958", setOf("org.telegram.messenger"))
        assertEquals(listOf(ContactActionKind.CALL, ContactActionKind.SMS, ContactActionKind.TELEGRAM), onlyTelegram.map { it.kind })

        // Local (no '+') numbers get no messenger deep links even if the apps are installed.
        val local = ContactActions.build("020 7946 0958", allInstalled)
        assertEquals(listOf(ContactActionKind.CALL, ContactActionKind.SMS), local.map { it.kind })
    }

    @Test fun invalidNumbersProduceNoActions() {
        assertTrue(ContactActions.build("n/a", allInstalled).isEmpty())
    }

    @Test fun messengerPackageListMatchesEnum() {
        assertEquals(
            setOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms"),
            ContactActions.messengerPackages.toSet()
        )
    }
}
