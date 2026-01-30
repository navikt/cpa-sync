package no.nav.emottak.cpa.nfs

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import no.nav.emottak.utils.environment.getEnvVar
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Vector

class NFSConnector(
    jSch: JSch = JSch()
) : AutoCloseable {

    private val privateKeyFile = "/var/run/secrets/privatekey"
    private val publicKeyFile = "/var/run/secrets/publickey"
    private val usernameMount = "/var/run/secrets/nfsusername"
    private val passphraseMount = "/var/run/secrets/passphrase"
    private val passphrase = String(File(passphraseMount).readBytes())
    private val username = String(File(usernameMount).readBytes())
    private val host = getEnvVar("NFS_HOST", "10.183.32.98")
    private val port = getEnvVar("NFS_PORT", "22").toInt()
    private val outboundCpa = "/outbound/cpa"
    private val channelType = "sftp"
    private val jsch: JSch = jSch
    private val session: Session
    private val sftpChannel: ChannelSftp

    private val log: Logger = LoggerFactory.getLogger("no.nav.emottak.smtp.cpasync")
    init {
        val knownHosts = Thread.currentThread().getContextClassLoader().getResourceAsStream("known_hosts")
        jsch.setKnownHosts(knownHosts)
        jsch.addIdentity(privateKeyFile, publicKeyFile, passphrase.toByteArray())
        session = jsch.getSession(username, host, port)
        session.userInfo = DummyUserInfo()
        session.connect()

        sftpChannel = session.openChannel(channelType) as ChannelSftp
        sftpChannel.connect()
        sftpChannel.cd(outboundCpa)
    }

    fun folder(): Vector<ChannelSftp.LsEntry> =
        sftpChannel.ls(outboundCpa) as Vector<ChannelSftp.LsEntry>

    fun file(filename: String): InputStream = sftpChannel.get(filename)

    fun rename(from: String, to: String) {
        // Assume we just overwrite (and are allowed to) if tofile already exists
        sftpChannel.rename(outboundCpa + "/" + from, outboundCpa + "/" + to)
    }
    fun createAndRemove(f: String) {
        val dst = outboundCpa + "/" + f
        val bis = ByteArrayInputStream("test".toByteArray())
        log.info("Putting to $dst")
        try {
            sftpChannel.put(bis, dst)
            log.info("Removing $dst")
            sftpChannel.rm(dst)
        } catch (e: Exception) {
            log.error(e.toString())
            var c = e.cause
            while (c != null) {
                log.error(c.toString())
                c = c.cause
            }
        }
        log.info("Done.")
    }

    override fun close() {
        sftpChannel.disconnect()
        session.disconnect()
    }
}

class DummyUserInfo : UserInfo {
    override fun getPassword(): String? {
        return passwd
    }

    override fun promptYesNo(str: String): Boolean {
        return true
    }

    var passwd: String? = null
    override fun getPassphrase(): String? {
        return null
    }

    override fun promptPassphrase(message: String): Boolean {
        return true
    }

    override fun promptPassword(message: String): Boolean {
        return true
    }

    override fun showMessage(message: String) {}
}
