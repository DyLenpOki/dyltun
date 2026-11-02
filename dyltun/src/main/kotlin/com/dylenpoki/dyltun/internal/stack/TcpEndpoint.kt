package com.dylenpoki.dyltun.internal.stack

import com.dylenpoki.dyltun.internal.packet.TcpPacket
import java.io.IOException
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.abs

internal enum class TcpState { SYN_RECEIVED, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, LAST_ACK, CLOSING, TIME_WAIT, CLOSED }

internal data class TcpOutput(
    val sequence: Long, val acknowledgement: Long, val flags: Int, val window: Int,
    val payload: ByteArray = byteArrayOf(), val mss: Int? = null
)

/**
 * A passive TCP endpoint. All methods and callbacks belong to the packet loop.
 * Wire sequence numbers are unwrapped near a live absolute sequence; windows stay below 2^31.
 */
internal class TcpEndpoint(
    syn: TcpPacket,
    initialSequence: Long,
    private val clock: MonotonicClock,
    private val emit: (TcpOutput) -> Unit,
    private val acknowledged: (Int) -> Unit,
    private val closed: (Throwable?) -> Unit,
    localMss: Int = 1460
) {
    private data class Sent(
        var sequence: Long, var payload: ByteArray, val flags: Int, val end: Long,
        val firstSent: Long, var retransmitted: Boolean = false, var attempts: Int = 0
    )
    val mss = minOf(syn.maximumSegmentSize() ?: 536, localMss)
    var state = TcpState.SYN_RECEIVED
        private set
    var handshakeComplete = false
        private set
    var clientFinished = false
        private set
    val clientAvailable: Int get() = received.size
    val availableSendBytes: Int get() = SEND_CAPACITY - sendUsed
    val receiveWindow: Int get() = (receiveRight - receiveNext).toInt().coerceIn(0, RECEIVE_CAPACITY)
    val terminal: Boolean get() = state == TcpState.CLOSED || state == TcpState.TIME_WAIT
    val pendingClientBytes: Int get() = inOrderHeld
    val transportClosed: Boolean get() = notified
    private val advertisedMss = localMss

    private val clientInitial = syn.sequence
    private var receiveNext = clientInitial + 1
    private var receiveRight = receiveNext + RECEIVE_CAPACITY
    private var inOrderHeld = 0
    private var delivered = 0
    private var pendingFin: Long? = null
    private val received = ByteRing(RECEIVE_CAPACITY)
    private val reordered = TreeMap<Long, ByteArray>()
    private val sending = ByteRing(SEND_CAPACITY)
    private var sendUsed = 0
    private val unacknowledged = ArrayDeque<Sent>()
    private var sendUna = wireSequence(initialSequence)
    private var sendNext = sendUna
    private var sendRight = sendUna + 1 + syn.window
    private var windowSequence = clientInitial
    private var windowAcknowledgement = sendUna
    private var congestionWindow = minOf(4 * mss, maxOf(2 * mss, 4380)).toLong()
    private var slowStartThreshold = SEND_CAPACITY.toLong()
    private var duplicateAcks = 0
    private var recoveryPoint: Long? = null
    private var smoothedRtt: Double? = null
    private var rttVariance = 0.0
    private var rto = SECOND
    private var retransmitAt = Long.MAX_VALUE
    private var persistAt = Long.MAX_VALUE
    private var persistDelay = SECOND
    private var lastByte: Byte? = null
    private var serverFinished = false
    private var finSequence: Long? = null
    private var lastActivity = clock.now()
    private var stateSince = lastActivity
    private var started = false
    private var notified = false

    init { require(mss > 0 && syn.flags and SYN != 0 && syn.flags and (ACK or RST) == 0) }

    fun start() {
        check(!started)
        started = true
        transmitNew(byteArrayOf(), SYN or ACK)
    }

    fun accept(packet: TcpPacket) {
        if (state == TcpState.CLOSED) return
        val now = clock.now()
        val sequence = unwrapSequence(packet.sequence, receiveNext)
        if (state == TcpState.TIME_WAIT) {
            if (packet.flags and RST == 0) {
                replyAck()
                if (packet.flags and FIN != 0 && sequence + packet.payloadLength + 1 == receiveNext) stateSince = now
            }
            return
        }
        if (packet.flags and RST != 0) {
            if (sequence == receiveNext) terminate(IOException("Client reset TCP connection"))
            else if (sequence in receiveNext until receiveRight) replyAck()
            return
        }
        if (packet.flags and SYN != 0) {
            if (state == TcpState.SYN_RECEIVED && packet.sequence == clientInitial && packet.flags and ACK == 0) {
                retransmit(unacknowledged.first)
            } else replyAck()
            return
        }
        val length = packet.payloadLength + if (packet.flags and FIN != 0) 1 else 0
        val acceptable = if (length == 0) {
            sequence == receiveNext || receiveWindow > 0 && sequence in receiveNext until receiveRight
        } else receiveWindow > 0 && sequence < receiveRight && sequence + length > receiveNext
        if (!acceptable) { replyAck(); return }
        if (packet.flags and ACK == 0) return
        val ack = unwrapSequence(packet.acknowledgement, sendUna)
        if (ack > sendNext) { replyAck(); return }
        if (state == TcpState.SYN_RECEIVED && ack != sendNext) return
        lastActivity = now
        val previousRight = sendRight
        val windowUpdated = sequence > windowSequence || sequence == windowSequence && ack >= windowAcknowledgement
        if (windowUpdated && ack >= sendUna) {
            sendRight = ack + packet.window
            windowSequence = sequence
            windowAcknowledgement = ack
        }
        if (ack > sendUna) {
            acknowledge(ack, now)
            if (state == TcpState.SYN_RECEIVED) { handshakeComplete = true; transition(TcpState.ESTABLISHED) }
            val fin = finSequence
            if (fin != null && ack > fin) when (state) {
                TcpState.FIN_WAIT_1 -> transition(TcpState.FIN_WAIT_2)
                TcpState.CLOSING -> timeWait()
                TcpState.LAST_ACK -> terminate(null)
                else -> Unit
            }
        } else if (ack == sendUna && packet.payloadLength == 0 && length == 0 &&
            previousRight == sendRight && unacknowledged.isNotEmpty() && handshakeComplete && packet.window > 0) {
            duplicateAcks++
            if (duplicateAcks == 3) {
                slowStartThreshold = maxOf((sendNext - sendUna) / 2, 2L * mss)
                congestionWindow = slowStartThreshold + 3L * mss
                recoveryPoint = sendNext
                retransmit(unacknowledged.first)
            } else if (duplicateAcks > 3) congestionWindow = minOf(SEND_CAPACITY.toLong(), congestionWindow + mss)
        }
        if (terminal) return
        if (!clientFinished && packet.payloadLength > 0) receivePayload(packet, sequence)
        if (!clientFinished && packet.flags and FIN != 0) {
            val fin = sequence + packet.payloadLength
            if (fin in receiveNext until receiveRight) {
                if (pendingFin == null || pendingFin == fin) {
                    pendingFin = fin
                    val iterator = reordered.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.key >= fin) iterator.remove()
                        else if (entry.key + entry.value.size > fin) entry.setValue(entry.value.copyOf((fin - entry.key).toInt()))
                    }
                }
                consumeFin()
            }
        }
        if (packet.payloadLength > 0 || packet.flags and FIN != 0) replyAck()
        pump()
    }

    private fun receivePayload(packet: TcpPacket, sequence: Long) {
        val start = maxOf(sequence, receiveNext)
        val end = minOf(sequence + packet.payloadLength, receiveRight, pendingFin ?: Long.MAX_VALUE)
        if (end <= start) return
        val gaps = mutableListOf<Pair<Long, Long>>()
        var cursor = start
        for ((position, bytes) in reordered) {
            if (position >= end) break
            if (position + bytes.size <= cursor) continue
            if (position > cursor) gaps += cursor to minOf(position, end)
            cursor = maxOf(cursor, position + bytes.size)
            if (cursor >= end) break
        }
        if (cursor < end) gaps += cursor to end
        if (reordered.size + gaps.size > 256) return
        for ((from, until) in gaps) {
            val offset = packet.payloadOffset + (from - sequence).toInt()
            reordered[from] = packet.ip.bytes.copyOfRange(offset, offset + (until - from).toInt())
        }
        while (true) {
            val bytes = reordered.remove(receiveNext) ?: break
            received.append(bytes)
            receiveNext += bytes.size
            inOrderHeld += bytes.size
        }
        consumeFin()
    }

    private fun consumeFin() {
        if (pendingFin != receiveNext || clientFinished) return
        clientFinished = true
        receiveNext++
        reordered.clear()
        when (state) {
            TcpState.ESTABLISHED -> transition(TcpState.CLOSE_WAIT)
            TcpState.FIN_WAIT_1 -> transition(TcpState.CLOSING)
            TcpState.FIN_WAIT_2 -> timeWait()
            else -> Unit
        }
    }

    fun peekClient(maximum: Int): ByteArray = received.peek(maximum)

    fun commitClient(length: Int) {
        received.discard(length)
        delivered += length
    }

    /** Only actual upstream writes (or an application handler consuming bytes) release receive credit. */
    fun consumedClient(length: Int) {
        require(length in 0..delivered)
        if (length == 0 || notified) return
        delivered -= length
        inOrderHeld -= length
        receiveRight += length
        if (!terminal) replyAck()
        else if (inOrderHeld == 0) { received.clear(); notifyClosed(null) }
    }

    fun offerServer(bytes: ByteArray): Boolean {
        check(!serverFinished && !terminal)
        if (bytes.size > availableSendBytes) return false
        sending.append(bytes)
        sendUsed += bytes.size
        if (bytes.isNotEmpty()) lastActivity = clock.now()
        pump()
        return true
    }

    fun finishServer() { if (!terminal) { serverFinished = true; pump() } }

    fun abort(cause: Throwable) {
        if (terminal) {
            if (!notified) { received.clear(); notifyClosed(cause) }
            return
        }
        emit(output(sendNext, RST or ACK))
        terminate(cause)
    }

    fun tick() {
        val now = clock.now()
        if (state == TcpState.CLOSED) {
            if (!notified && now - stateSince >= 60 * SECOND) abort(IOException("TCP close drain timed out"))
            return
        }
        if (state == TcpState.TIME_WAIT) {
            if (now - stateSince >= 60 * SECOND) terminate(if (inOrderHeld == 0) null else IOException("TCP close drain timed out"))
            return
        }
        if (now - lastActivity >= 300 * SECOND || state == TcpState.FIN_WAIT_2 && now - stateSince >= 60 * SECOND) {
            abort(IOException("TCP connection timed out")); return
        }
        val zeroWindow = handshakeComplete && sendRight <= sendUna && (sending.size > 0 || unacknowledged.isNotEmpty() || serverFinished && finSequence == null)
        if (zeroWindow) {
            if (persistAt == Long.MAX_VALUE) persistAt = now + persistDelay
            if (now >= persistAt) {
                val payload = lastByte?.let { byteArrayOf(it) } ?: sending.peek(1)
                emit(output(sendNext - 1, ACK, payload))
                persistDelay = minOf(60 * SECOND, persistDelay * 2)
                persistAt = now + persistDelay
            }
        } else {
            persistAt = Long.MAX_VALUE
            persistDelay = SECOND
            if (unacknowledged.isNotEmpty() && now >= retransmitAt) {
                val first = unacknowledged.first
                if (++first.attempts > 8) { abort(IOException("TCP retransmission limit reached")); return }
                slowStartThreshold = maxOf((sendNext - sendUna) / 2, 2L * mss)
                congestionWindow = mss.toLong()
                duplicateAcks = 0
                recoveryPoint = null
                retransmit(first)
                rto = minOf(60 * SECOND, rto * 2)
                retransmitAt = now + rto
            }
        }
        pump()
    }

    private fun acknowledge(ack: Long, now: Long) {
        var released = 0
        var sample: Long? = null
        while (unacknowledged.isNotEmpty()) {
            val sent = unacknowledged.first
            if (ack <= sent.sequence) break
            val amount = minOf(ack, sent.end) - sent.sequence
            if (sent.payload.isNotEmpty()) released += amount.toInt()
            if (ack >= sent.end) {
                if (!sent.retransmitted && sample == null) sample = now - sent.firstSent
                unacknowledged.removeFirst()
            } else {
                sent.payload = sent.payload.copyOfRange(amount.toInt(), sent.payload.size)
                sent.sequence = ack
                break
            }
        }
        sendUna = ack
        sendUsed -= released
        sample?.let {
            val current = smoothedRtt
            if (current == null) { smoothedRtt = it.toDouble(); rttVariance = it / 2.0 }
            else {
                rttVariance = 0.75 * rttVariance + 0.25 * abs(current - it)
                smoothedRtt = 0.875 * current + 0.125 * it
            }
        }
        val srtt = smoothedRtt
        rto = if (srtt == null) SECOND else (srtt + maxOf(1_000_000.0, 4 * rttVariance)).toLong().coerceIn(SECOND, 60 * SECOND)
        retransmitAt = if (unacknowledged.isEmpty()) Long.MAX_VALUE else now + rto
        val recovery = recoveryPoint
        if (recovery != null) {
            if (ack >= recovery) { congestionWindow = slowStartThreshold; recoveryPoint = null }
            else if (unacknowledged.isNotEmpty()) retransmit(unacknowledged.first)
        } else if (released > 0) {
            val growth = if (congestionWindow < slowStartThreshold) minOf(released, mss).toLong()
                else maxOf(1L, mss.toLong() * mss / congestionWindow)
            congestionWindow = minOf(SEND_CAPACITY.toLong(), congestionWindow + growth)
        }
        duplicateAcks = 0
        if (released > 0) acknowledged(released)
    }

    private fun pump() {
        if (!handshakeComplete || terminal) return
        while (sending.size > 0 && unacknowledged.size < 256) {
            val space = minOf(sendRight - sendNext, congestionWindow - (sendNext - sendUna))
            if (space <= 0) break
            val bytes = sending.peek(minOf(mss.toLong(), space).toInt())
            sending.discard(bytes.size)
            lastByte = bytes.last()
            transmitNew(bytes, ACK or PSH)
        }
        if (serverFinished && finSequence == null && sending.size == 0 && sendNext < sendRight && sendNext - sendUna < congestionWindow) {
            finSequence = sendNext
            transmitNew(byteArrayOf(), FIN or ACK)
            transition(if (clientFinished) TcpState.LAST_ACK else TcpState.FIN_WAIT_1)
        }
    }

    private fun transmitNew(payload: ByteArray, flags: Int) {
        val now = clock.now()
        val end = sendNext + payload.size + (if (flags and SYN != 0 || flags and FIN != 0) 1 else 0)
        if (unacknowledged.isEmpty()) retransmitAt = now + rto
        unacknowledged += Sent(sendNext, payload, flags, end, now)
        emit(output(sendNext, flags, payload))
        sendNext = end
    }

    private fun retransmit(sent: Sent) {
        sent.retransmitted = true
        emit(output(sent.sequence, sent.flags, sent.payload))
    }

    private fun output(sequence: Long, flags: Int, payload: ByteArray = byteArrayOf()) = TcpOutput(
        wireSequence(sequence), wireSequence(receiveNext), flags, receiveWindow, payload,
        if (flags and SYN != 0) advertisedMss else null
    )

    private fun replyAck() { emit(output(sendNext, ACK)) }
    private fun transition(next: TcpState) { state = next; stateSince = clock.now() }

    private fun timeWait() { transition(TcpState.TIME_WAIT); releaseSending(); if (inOrderHeld == 0) notifyClosed(null) }

    private fun terminate(cause: Throwable?) {
        transition(TcpState.CLOSED)
        releaseSending()
        if (cause != null || inOrderHeld == 0) { received.clear(); notifyClosed(cause) }
    }
    private fun notifyClosed(cause: Throwable?) { if (!notified) { notified = true; closed(cause) } }
    private fun releaseSending() { sending.clear(); reordered.clear(); unacknowledged.clear() }

    companion object {
        const val RECEIVE_CAPACITY = 65535
        const val SEND_CAPACITY = 65536
        const val FIN = 1; const val SYN = 2; const val RST = 4; const val PSH = 8; const val ACK = 16
        private const val SECOND = 1_000_000_000L
    }
}
