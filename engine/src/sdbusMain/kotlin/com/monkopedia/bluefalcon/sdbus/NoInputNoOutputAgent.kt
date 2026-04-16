package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.bluefalcon.sdbus.bluez.Agent1Adaptor
import com.monkopedia.sdbus.Object
import com.monkopedia.sdbus.ObjectPath

/**
 * BlueZ Agent1 implementation that auto-accepts all pairing requests.
 * NoInputNoOutput ("Just Works") is the only mode we can support since the
 * engine's createBond/removeBond surface doesn't expose PIN/passkey callbacks.
 */
internal class NoInputNoOutputAgent(obj: Object) : Agent1Adaptor(obj) {
    override suspend fun release() {}
    override suspend fun requestPinCode(device: ObjectPath): String = "0000"
    override suspend fun displayPinCode(device: ObjectPath, pincode: String) {}
    override suspend fun requestPasskey(device: ObjectPath): UInt = 0u
    override suspend fun displayPasskey(device: ObjectPath, passkey: UInt, entered: UShort) {}
    override suspend fun requestConfirmation(device: ObjectPath, passkey: UInt) {}
    override suspend fun requestAuthorization(device: ObjectPath) {}
    override suspend fun authorizeService(device: ObjectPath, uuid: String) {}
    override suspend fun cancel() {}
}
