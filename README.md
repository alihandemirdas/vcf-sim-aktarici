# VCF → SIM

iPhone'dan aldığın `.vcf` rehber yedeğini, bir Android telefon üzerinden **SIM karta** yazan küçük, ücretsiz bir Android uygulaması.
Tuşlu telefona geçecekler (ör. askere gidenler) için yapıldı.

**[⬇️ APK'yı indir (Releases)](../../releases/latest)**

## Ne yapar?
1. Dosyalardan `.vcf` dosyasını seçersin (iPhone, Android, Google dışa aktarımları; vCard 2.1 / 3.0 / 4.0)
2. Hangi SIM'e yazılacağını seçersin (çift SIM destekli)
3. Aktarmak istediğin kişileri işaretlersin (arama, tümünü seç, temizle)
4. **SIM'e yaz** dersin. SIM'i tuşlu telefona takarsın, rehberin orada

## Özellikler
- SIM kapasitesini ve kalan boş yeri gösterir (SIM'ler genelde 250 kişi alır)
- Her kişinin SIM'e **tam olarak nasıl yazılacağını** önceden gösterir (SIM'de isim alanı genelde 14 harf)
- **Türkçe harfleri sadeleştirme:** ş, ğ, ı SIM'in standart alfabesinde yok. Bunlar varsa isim 6 harfe düşer. Sadeleştirince isim 14 harfe kadar sığar
- Birden fazla numarası olan kişiler ayrı satır olur ("Ahmet Cep", "Ahmet Ev")
- SIM'de zaten olan numaraları işaretler, aynı kişiyi iki kez yazmazsın

## Gizlilik
Uygulamanın **internet izni yok**. Kişilerin telefondan hiçbir yere gönderilmez. İstenen izinler:
- **Kişiler**: Android'de SIM rehberine okuma/yazma bu izinle yapılır
- **Telefon**: takılı SIM'leri listelemek için

## iPhone'dan .vcf nasıl alınır?
Kişiler uygulaması › Listeler › **Tüm Kişiler**'e basılı tut › **Dışa Aktar**.
Ya da iCloud.com › Kişiler › tümünü seç › vCard olarak dışa aktar.

## Bilinen sınırlamalar
- Çalıştırmak için bir **Android telefon** gerekir (Android 6+). iPhone SIM karta yazamıyor
- Bazı üreticiler (ör. bazı Xiaomi modelleri) uygulamaların SIM'e yazmasını engelleyebilir. Olmazsa başka bir Android telefonda dene
- APK Play Store dışından yüklendiği için Android "bilinmeyen kaynak" uyarısı gösterebilir

## Derleme
Gradle gerekmez. Android SDK (build-tools 35, platform android-35) ve JDK 17 ile:

```bash
./build.sh
```
