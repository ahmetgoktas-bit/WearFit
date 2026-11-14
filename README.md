# WearFit Companion - Android Akıllı Saat Yöneticisi

H10 ve Wearfit / Howear ekosistemindeki akıllı saatler için özel olarak geliştirilmiş modern, açık kaynaklı ve gizlilik odaklı Native Android (Kotlin) uygulaması.

---

## ✨ Özellikler

1. **Bluetooth Low Energy (BLE) Entegrasyonu:**
   - Çevredeki H10 ve uyumlu akıllı saatleri otomatik tarama ve listeleme.
   - Kararlı GATT bağlantısı ve servis/karakteristik keşfi.
2. **Saat ve Tarih Senkronizasyonu:**
   - Saate telefonun anlık tarih/saat bilgisini iletme (`0x93` komutu).
3. **Sensör ve Sağlık Takibi:**
   - Adım sayısı, anlık nabız (BPM) ve yakılan kalori değerlerini saatin sensörlerinden okuma (`0x32` komutu).
   - Saatin anlık pil yüzdesini takip etme (`0x91` komutu).
4. **Cihazımı Bul (Titreşim):**
   - Saati titreterek yerini bulmanızı sağlayan sinyal komutu (`0x71` komutu).
5. **Otomatik Bildirim İletici (NotificationForwarderService):**
   - Telefona gelen **WhatsApp, Telegram, Instagram, SMS, Arama** bildirimlerini arka planda yakalayarak saatin ekranına özel uygulama ikonları ile doğrudan iletir.
6. **Android 14 ve 15 Tam Uyumluluğu:**
   - `targetSdk 35` ile en güncel Android izin ve güvenlik modellerini (Nearby Devices, Notification Listener, Foreground Service) eksiksiz uygular.

---

## 🛠️ Proje Mimarisi

```
app/src/main/
├── AndroidManifest.xml
├── java/com/wearfit/companion/
│   ├── MainActivity.kt                      # Ana arayüz ve BLE kontrol akışı
│   ├── ble/
│   │   ├── BleManager.kt                    # GATT bağlantısı ve I/O yöneticisi
│   │   ├── BleScanner.kt                    # BLE cihaz tarayıcısı
│   │   └── WearfitProtocol.kt               # Wearfit hex komut ve paket protokolü
│   ├── model/
│   │   ├── BleDeviceItem.kt                 # Taranan cihaz veri modeli
│   │   └── WatchData.kt                     # Saat sağlık ve pil durumu modeli
│   ├── service/
│   │   └── NotificationForwarderService.kt  # WhatsApp/SMS bildirimlerini saate ileten servis
│   └── ui/
│       └── DeviceAdapter.kt                 # Taranan cihazlar RecyclerView adaptörü
└── res/
    ├── layout/
    │   ├── activity_main.xml                # Modern koyu tema kontrol paneli
    │   ├── item_device.xml                  # Cihaz liste kartı
    │   └── dialog_test_notification.xml     # Test bildirim penceresi
    └── values/
        ├── colors.xml                       # Modern siber-koyu tema renkleri
        ├── strings.xml                      # Türkçe metin ve etiketler
        └── themes.xml                       # Material 3 teması
```

---

## 🚀 Projeyi Çalıştırma (Android Studio)

1. **Android Studio'yu Açın:**
   - Menüden `File` -> `Open` seçeneğine tıklayın.
   - `c:\Users\HP\.copilot\repos\_privateProjects\WearFit` klasörünü seçin.
2. **Gradle Senkronizasyonu:**
   - Android Studio projeyi otomatik olarak tanıyacak ve bağımlılıkları indirecektir.
3. **Cihazınıza Yükleyin:**
   - Telefonunuzu USB kablo ile bilgisayara bağlayın (veya Wi-Fi Debugging kullanın).
   - Android Studio'da yeşil **Run (`Shift + F10`)** butonuna basın.

---

## 📱 Uygulama İçi İzinler

- **Bluetooth ve Konum:** İlk açılışta çevredeki saatleri bulabilmek için "Yakındaki Cihazlar" (Nearby Devices) iznini onaylayın.
- **Bildirim Erişimi:** Ana ekranda beliren turuncu karttaki **"Bildirim İznini Aç"** butonuna dokunarak telefonunuzun ayarlarında *WearFit Companion* uygulamasına izin verin. Bu sayede WhatsApp ve SMS mesajları saatinize iletilir.
