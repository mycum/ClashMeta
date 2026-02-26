package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import java.util.UUID

@Suppress("unused")
class MainApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            setupDefaultProfile()
            startLocalInterceptor()
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun setupDefaultProfile() {
        val prefs = getSharedPreferences("auto_setup", Context.MODE_PRIVATE)
        if (prefs.getBoolean("setup_done", false)) return

        try {
            val profilesDir = File(filesDir, "profiles")
            val pendingDir = File(filesDir, "pending")
            profilesDir.mkdirs()
            pendingDir.mkdirs()

            // Наш идеальный минималистичный конфиг
            val defaultConfig = """
                mixed-port: 7890
                allow-lan: false
                mode: rule
                log-level: warning
                ipv6: false

                dns:
                  enable: true
                  ipv6: false
                  enhanced-mode: fake-ip
                  nameserver:
                    - https://8.8.8.8/dns-query
                    - https://1.1.1.1/dns-query
                  fallback:
                    - https://dns.cloudflare.com/dns-query
                    - https://dns.google/dns-query

                proxy-providers:
                  rus_servers:
                    type: http
                    url: "http://127.0.0.1:9090/sub"
                    interval: 86400
                    path: ./providers/rus.yaml
                    health-check:
                      enable: true
                      interval: 600
                      url: "http://cp.cloudflare.com/generate_204"

                proxy-groups:
                  - name: auto
                    type: url-test
                    use:
                      - rus_servers
                    url: "http://cp.cloudflare.com/generate_204"
                    interval: 300
                    tolerance: 50

                rules:
                  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
                  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
                  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
                  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
                  - DOMAIN-SUFFIX,localhost,DIRECT
                  - MATCH, auto
            """.trimIndent()

            val uuid = UUID.randomUUID().toString()
            val configFile = File(profilesDir, "$uuid.yaml")
            configFile.writeText(defaultConfig)

            // Настраиваем базу данных
            val db = com.github.kr328.clash.service.data.Database.invoke(this)
            
            // Включаем раздельное туннелирование (только Telegram)
            val serviceStore = com.github.kr328.clash.service.store.ServiceStore(this)
            serviceStore.accessControlMode = com.github.kr328.clash.service.model.AccessControlMode.Accept
            serviceStore.accessControlPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web")

            // Добавляем профиль в базу
            val profile = com.github.kr328.clash.service.model.Profile(
                uuid = uuid,
                name = "Telegram Auto",
                type = com.github.kr328.clash.service.model.Profile.Type.File,
                source = "",
                interval = 0,
                pending = false,
                imported = true,
                createdAt = System.currentTimeMillis()
            )
            
            db.profileDao().insert(profile)
            
            // Делаем его активным
            val selection = com.github.kr328.clash.service.data.Selection(
                id = 0,
                selected = uuid
            )
            db.selectionDao().setSelected(selection)

            prefs.edit().putBoolean("setup_done", true).apply()
        } catch (e: Exception) {
            Log.e("Failed to setup default profile", e)
        }
    }

    private fun startLocalInterceptor() {
        thread(start = true, isDaemon = true) {
            try {
                val serverSocket = ServerSocket(9090)
                Log.i("Local interceptor started on port 9090")
                while (true) {
                    val client = serverSocket.accept()
                    thread {
                        try {
                            val reader = client.inputStream.bufferedReader()
                            val requestLine = reader.readLine()
                            if (requestLine != null && requestLine.startsWith("GET /sub")) {
                                Log.i("Intercepting subscription request")
                                // Скачиваем оригинальную подписку
                                val rawSub = URL("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_SS%2BAll_RUS.txt").readText()
                                // ДЕЛАЕМ ЗАМЕНУ ШИФРА
                                val fixedSub = rawSub.replace("chacha20-poly1305", "chacha20-ietf-poly1305")
                                
                                val response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain; charset=utf-8\r\n" +
                                        "Connection: close\r\n" +
                                        "\r\n" +
                                        fixedSub
                                
                                client.outputStream.write(response.toByteArray())
                                client.outputStream.flush()
                            }
                        } catch (e: Exception) {
                            Log.e("Interceptor error", e)
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Failed to start interceptor", e)
            }
        }
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}