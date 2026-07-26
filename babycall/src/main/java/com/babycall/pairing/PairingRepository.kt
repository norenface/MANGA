package com.babycall.pairing

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.Firebase
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

data class FamilySettings(
    val pinHash: String? = null,
    val autoAnswer: Boolean = true
)

class PairingException(message: String) : Exception(message)

/**
 * Handles the one-time linking between a parent device and a baby device.
 *
 * A baby device can only ever join the single family whose 6-digit code was
 * shown on the parent's screen and typed in by hand; there is no directory,
 * no contact list, and no way to browse or guess into another family from
 * the baby app, since a redeemed code is deleted immediately (single use)
 * and expires after 10 minutes if unused.
 */
class PairingRepository {

    private val db: FirebaseDatabase = Firebase.database
    private val root get() = db.reference

    suspend fun createFamily(deviceId: String, parentName: String): String {
        val familyRef = root.child("families").push()
        val familyId = familyRef.key ?: throw PairingException("familyId生成に失敗しました")

        familyRef.child("members").child(deviceId).setValue(
            mapOf(
                "deviceId" to deviceId,
                "role" to "parent",
                "name" to parentName,
                "lastSeen" to System.currentTimeMillis()
            )
        ).await()

        familyRef.child("settings").setValue(
            mapOf(
                "autoAnswer" to true
            )
        ).await()

        familyRef.child("call").child("state").setValue("idle").await()

        return familyId
    }

    suspend fun generatePairingCode(familyId: String): String {
        val code = (100000 + Random.nextInt(900000)).toString()
        root.child("pairing_codes").child(code).setValue(
            mapOf(
                "familyId" to familyId,
                "createdAt" to System.currentTimeMillis()
            )
        ).await()
        return code
    }

    suspend fun redeemPairingCode(code: String, deviceId: String, babyName: String): String {
        val snapshot = root.child("pairing_codes").child(code).get().await()
        if (!snapshot.exists()) {
            throw PairingException("コードが見つかりません。もう一度確認してください。")
        }
        val familyId = snapshot.child("familyId").getValue(String::class.java)
            ?: throw PairingException("コードが壊れています。")
        val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
        if (System.currentTimeMillis() - createdAt > CODE_EXPIRY_MS) {
            root.child("pairing_codes").child(code).removeValue()
            throw PairingException("コードの有効期限が切れています。親アプリで再発行してください。")
        }

        root.child("families").child(familyId).child("members").child(deviceId).setValue(
            mapOf(
                "deviceId" to deviceId,
                "role" to "baby",
                "name" to babyName,
                "lastSeen" to System.currentTimeMillis()
            )
        ).await()

        // Single use: remove immediately so the code can't be reused or shared.
        root.child("pairing_codes").child(code).removeValue().await()

        return familyId
    }

    suspend fun unpairDevice(familyId: String, deviceId: String) {
        root.child("families").child(familyId).child("members").child(deviceId).removeValue().await()
    }

    suspend fun getSettings(familyId: String): FamilySettings {
        val snapshot = root.child("families").child(familyId).child("settings").get().await()
        return FamilySettings(
            pinHash = snapshot.child("pinHash").getValue(String::class.java),
            autoAnswer = snapshot.child("autoAnswer").getValue(Boolean::class.java) ?: true
        )
    }

    suspend fun setPin(familyId: String, pinHash: String) {
        root.child("families").child(familyId).child("settings").child("pinHash").setValue(pinHash).await()
    }

    suspend fun setAutoAnswer(familyId: String, enabled: Boolean) {
        root.child("families").child(familyId).child("settings").child("autoAnswer").setValue(enabled).await()
    }

    /** Live-listens to family settings (PIN hash + auto-answer). Returns the listener so callers can remove it. */
    fun observeSettings(familyId: String, onChange: (FamilySettings) -> Unit): ValueEventListener {
        val ref = root.child("families").child(familyId).child("settings")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(
                    FamilySettings(
                        pinHash = snapshot.child("pinHash").getValue(String::class.java),
                        autoAnswer = snapshot.child("autoAnswer").getValue(Boolean::class.java) ?: true
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) { /* transient; next write will retry */ }
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun removeSettingsListener(familyId: String, listener: ValueEventListener) {
        root.child("families").child(familyId).child("settings").removeEventListener(listener)
    }

    suspend fun findBabyDeviceId(familyId: String): String? {
        val snapshot = root.child("families").child(familyId).child("members").get().await()
        for (child in snapshot.children) {
            if (child.child("role").getValue(String::class.java) == "baby") {
                return child.key
            }
        }
        return null
    }

    companion object {
        private const val CODE_EXPIRY_MS = 10 * 60 * 1000L
    }
}
