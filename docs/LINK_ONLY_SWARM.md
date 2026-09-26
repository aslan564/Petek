# Yalnız link ilə sürü — toplanmış ideyalar

**Status:** sahibin qərarları verilib (2026-09-26); hələ `PLAN.md`, tələb və ADR sənədlərinə köçürülməyib ·
**Mənbə:** sahibin 2026-09-26 söhbətində göndərdiyi ideya mətnləri, düzəlişləri və təkliflərə cavabları

Bu sənəd ideyaları bir yerə yığır, Pətəkin bugünkü kodu və `PLAN.md` ilə tutuşdurur və sahibin qərarlarını qeyd edir.

## 0. Sahibin qərarları (2026-09-26)

Bu bölmə üstündür: aşağıdakı bölmələr onunla toqquşarsa, bu bölmə keçərlidir.

### Əsas on bənd

1. Pətək saytda həm böyük çöküşləri, həm də xırda xətaları tapır.
2. Kəşfiyyatçı əvvəl saytın növünü təyin edir, çünki mağaza, xəbər saytı, vitrin və giriş sistemi fərqli yoxlanır.
3. Kəşfiyyatçı əvvəl çöldən qeydiyyat və login qapısını tapır, sonra bir hesabla içəri girib evin xəritəsini çıxarır.
4. Hər tester ayrıca brauzer alır. Bir hesab eyni anda yalnız bir testerdə olur. Testi aparan adam icazə verəndə
   orkestrator testini bitirmiş testerlərin hesablarını dəyişdirir və onlar yenidən test edir, baxış bucağı dəyişsin deyə.
5. Sahib ssenaridən əvvəl verdiyi təlimat sənədində hesab yazıbsa, bəzi testerlər o hesablarla girir. Qalanları
   qapıdan öz təzə hesabını yaradır. Təlimatdakı hesablar mütləq test hesablarıdır, real istifadəçinin hesabı ola
   bilməz; bu, alətin istifadə qaydalarında yazılır.
6. Təzə hesabın kodu testerə məxsus poçt ünvanına gəlir və Pətək onu özü oxuyur (harada: bənd 14). Qutu yoxdursa və
   ya CAPTCHA qapını bağlayırsa, alət səbəbi deyib dayanır.
7. AI qapını yalnız bir dəfə öyrənir. Ssenari hər testerin qeydiyyat, login və ya qonaq qapısından hansını keçəcəyini
   yazır. Heç kəsin hesabı yoxdursa, hamı öz hesabını yaradır. Qalan testerlər o yolu kodla, ucuz və eyni cür keçir.
8. Hamı içəri girəndən sonra hər tester ayrı kart alır və testerlər bir-birini görmür. Orkestrator hamını idarə edir.
   Kəşfiyyatçı proses bitənə qədər kəşfiyyata davam edir. Onun tapdıqları növbəti run-ın ssenarisini genişləndirir,
   testerlər növbəti run-da yeni ssenari ilə davam edir.
9. Testlər production-da yox, pre və ya stage mühitində aparılır. Testləri aparan adam icazə verəndən sonra bütün
   testlər tam aparılır.
10. Hesabat hər tapıntını sayt xətası, alət boşluğu və ya ssenari səhvi kimi ayırır. O, həm ətraflıdır, həm də müştəri
    üçün çox sadədir. Birinci run növbəti run üçün hazır ssenari qoyur.

### Açıq suallara cavablar

11. **Başqa testerə aid dəyər.** Testerlər bir-birinin adını, e-poçtunu və rolunu bilmir, bugünkü həmkar siyahısı
    götürülür. Dəvət ünvanı və ya başqasının sifariş URL-i lazımdırsa, orkestrator onu kartın içinə yer tutucu kimi
    qoyur, kimin olduğunu demədən. Pətək dəyəri son anda yazır, parolda olduğu kimi.
12. **Kəşfiyyatçının hesabı.** Kəşfiyyatçının öz hesabı var və o, heç vaxt testerlə paylaşılmır. Təlimatda kəşfiyyatçı
    üçün login və parol verilibsə onu işlədir, verilməyibsə qapıdan özünə hesab yaradır. Admin hesabında yalnız adında
    Pətək işarəsi olan obyektlər yaradır və sonda silir. Mövcud dataya, istifadəçilərə və ayarlara toxunmur.
13. **IP.** Paneldə "hər testerə ayrı IP" seçimi var. Seçilibsə, hər tester ayrı IP-dən gəlir və sayt, xüsusən realtime
    testdə, hər testeri ayrı istifadəçi kimi görür. Seçilməyibsə, Pətək IP limitini tanıyır, tapıntını səbəbi ilə
    "alət boşluğu" rəfinə yazır, testerlərin başlanğıcını zamana yayır və stage-də test IP-sinin limitdən azad
    edilməsini tövsiyə edir. Seçim yalnız sahibliyi təsdiqlənmiş saytda açılır (bölmə 7).
14. **Poçt.** Əvvəl sahibin öz poçt qutusu və "artı ünvan" IMAP ilə, developer üçün lokal Mailpit. Pətəkin öz
    serverindəki qutu sonra gəlir, ödənişli modul kimi: yalnız qəbul edir, məktubları bir gündən sonra silir, yalnız
    run sahibinə göstərir.
15. **Demo.** Ghost (xəbər) və WooCommerce (mağaza) sahibin öz serverində qaldırılır. KadroHR laboratoriya qalır.
16. **Sahiblik.** Tam test yalnız sahibliyi təsdiqlənmiş sayta aparılır: saytın kökündə Pətəkin verdiyi kodla fayl və
    ya DNS qeydi. Bu, bir dəfə edilir və yadda qalır. Localhost və daxili şəbəkə ünvanları təsdiqsiz qəbul olunur.
    Təsdiq yoxdursa, Pətək yalnız oxuyur.

## 1. Məqsəd

- Sahib link verir, istəsə təlimat sənədi də. Pətək saytı özü öyrənir, testerləri içəri salır, hər testerə ayrı iş
  verir, sübutlu hesabat qalır.
- Pətək həm böyük çöküşləri (5xx, boş səhifə, konsol xətası, qırılan axın), həm də xırda xətaları tapır. Xırda xəta
  kataloqu onu başqa alətlərdən fərqləndirir.
- Alətin iki üzü var. **Başla**: link, tester sayı, düymə. **Mühərrik**: YAML, MCP, `--ci`, assert, triaj. Hesabat eynidir.
- İki rejim ardıcıldır. Birinci run "özü öyrən" rejimidir. Onun kartları dondurulmuş YAML olur və ikinci run
  "kampaniya" rejimində təkrarlanır və ölçülür.

## 2. Bir run-ın ömrü

1. **Link, sahiblik, mühit və icazə.** Hədəf pre və ya stage mühitidir. Tam test üçün saytın sahibliyi bir dəfə
   təsdiqlənir və testləri aparan adam icazə verir (bölmə 7).
2. **Yoxlama.** Sayt cavab verir? Qeydiyyat, login, qonaq girişi, OTP, CAPTCHA var? Poçt qutusu hazırdır? "Hər testerə
   ayrı IP" seçilibsə, hər testerə IP çatır?
3. **Keçid 0 — küçə.** Kəşfiyyatçı anonim gəzir, saytın növünü təyin edir və qapının xəritəsini çıxarır: qeydiyyat,
   login, qonaq girişi, OTP növü (e-poçt kodu, link, SMS), şifrəni unutdum, CAPTCHA, dəvət.
4. **Keçid 1 — ev.** Kəşfiyyatçı öz hesabı ilə qapıdan keçir: təlimatda onun üçün hesab varsa onunla, yoxdursa özünə
   açdığı hesabla. Evin xəritəsini çıxarır: səhifələr, rollar, "yarat" düymələri, icazə URL-ləri, canlı səthlər.
   Çöldən yazılan ssenari login-dən sonrakını görmür.
5. **Orkestratorun planı.** Ssenari hər testerin hansı qapıdan keçəcəyini yazır: qeydiyyat, login və ya qonaq. Login
   hesabları təlimat sənədindən gəlir; hesabı olmayan tester qapıdan öz hesabını yaradır. Başqa testerə aid dəyər
   kartın içinə yer tutucu kimi qoyulur. Dalğalar və kartlar da burada.
6. **Qapı dalğası.** Hamı paralel, eyni öyrənilmiş axınla, kodla keçir. AI burada işləmir.
7. **Qapı baryeri.** "İçəridəyəm" siqnalı: çıxış düyməsi, panel, cookie. Keçməyən tester missiya almır.
8. **Ev dalğası.** Orkestrator kartları paylayır və hamını idarə edir: `emits`, `wait_for`, assert. Kəşfiyyatçı proses
   bitənə qədər kəşfiyyata davam edir. Tapdıqları cari run-a yox, növbəti run-ın ssenarisinə gedir.
9. **Hesab dəyişdirmə (istəyə bağlı).** Testi aparan adam icazə verəndə testini bitirmiş testerlər hesabları dəyişdirib
   yenidən test edir.
10. **Hesabat, təmizlik, genişlənmiş ssenari.** Birinci run-ın kartları və kəşfiyyatçının yeni tapdıqları növbəti
    run-ın YAML-ı olur.

Keçid 1 alınmırsa run dayanır və səbəb deyilir. Kəşfiyyatçı qapını tapmayıbsa, qalan testerlərə "sən tap" deyilmir.

## 3. Dəmir qaydalar

1. **Bir tester, bir ayrı brauzer mühiti. Bir hesab eyni anda yalnız bir testerdə.** Hesab dəyişdirmə yalnız testi
   aparan adamın icazəsi ilə və yalnız testini bitirmiş testerlər arasında olur. Əvvəlki tester çıxış edir, növbəti
   tester öz təzə brauzerində girir. Sübutda hər addımın hansı hesabla atıldığı yazılır.
2. **Testerlər bir-birini görmür:** nə adını, nə e-poçtunu, nə rolunu, nə hesabını, nə ekranını, nə şifrəsini, nə
   OTP-sini, nə addımını. Bugünkü həmkar siyahısı götürülür. Başqa testerə aid dəyər lazımdırsa, orkestrator onu
   kartda yer tutucu kimi verir və Pətək son anda yazır. Hesab başqa testerə keçəndə də yeni tester onu əvvəl kimin
   işlətdiyini bilmir. Hadisə şini söhbət deyil, metronomdur: "indi bax", "indi gözlə".
3. **Hamını izləyən və idarə edən yalnız orkestrator və sahibdir** (panel).

```text
bir hesab eyni anda yalnız bir testerdə (icazə ilə, testini bitirənlər arasında dəyişdirilə bilər)
kəşfiyyatçının hesabı ∩ testerlərin hesabları = boş
təlimatdakı hesablar ∩ təzə hesablar = boş
hər təzə e-poçt unikaldır
hər poçt ünvanı yalnız bir testerə məxsusdur
```

## 4. Qapılar və hesablar

| Kim | Qapı | Hesab haradan |
|---|---|---|
| Kəşfiyyatçı | login və ya qeydiyyat | təlimatda onun üçün ayrıca test hesabı, yoxdursa qapıdan özünə təzə hesab; testerlərlə heç vaxt paylaşılmır |
| Tester A | login | sahibin təlimat sənədindəki test hesabı |
| Tester B | qeydiyyat | qapıdan təzə hesab, OTP öz ünvanına |
| Qonaq tester | qonaq | hesab yoxdur, öz cookie-si, dili, razılıq banneri |
| Tester C | mənfi yollar: səhv OTP, təkrar qeydiyyat, səhv şifrə, icazəsiz URL | öz hesabı ilə, bilərəkdən səhv |

- Heç kəsin hesabı yoxdursa, hamı qeydiyyat qapısından öz hesabını yaradır və öz ssenarisi ilə davam edir.
- Qeydiyyat qapısı yoxdursa, eyni anda hesabla işləyən tester sayı hesab sayından çox ola bilməz. Qalan testerlər qonaq
  qapısından gedir. Testini bitirənlər testi aparan adamın icazəsi ilə hesabları dəyişə bilər. Panel bunu əvvəldən deyir.
- OTP yoxdursa B sadələşir: qeydiyyatdan sonra birbaşa davam edir.
- Sayt OTP istəyir və poçt qutusu yoxdursa B açılmır. A varsa yalnız A işləyir, yoxdursa run səbəblə dayanır.
- Kəşfiyyatçı admin hesabında yalnız adında Pətək işarəsi olan obyektlər yaradır və sonda silir. Mövcud dataya,
  istifadəçilərə və ayarlara toxunmur.
- Təlimatdakı hesablar mütləq test hesablarıdır, real istifadəçinin hesabı ola bilməz. Bu, alətin istifadə qaydalarında
  yazılır: README-nin "öz saytınızda istifadə" bölməsi (EN/AZ), `SECURITY.md` və skill paketinin qaydaları.
- Sahib parolu təlimata rahatca yaza bilər. Qayda 10 və skill paketinin 5-ci qaydası test parollarını da sayır, ona görə
  kod parolu sənəddən ayırıb `Secret` kimi saxlayır və AI yalnız yer tutucu görür. Sahib üçün heç nə dəyişmir.

## 5. Poçt qutusu

"Yalnız link" vədinin şərti budur: sayt kodu real e-poçta göndərir, Pətək onu oxuya bilməlidir.

| Yol | Nə vaxt | Nə vaxt tikilir |
|---|---|---|
| Sahibin poçt qutusu + "artı ünvan", IMAP ilə | default: qutu `test@sirket.az`-dirsə, testerlər `test+b7@sirket.az` alır, hamısı bir qutuya düşür, Pətək alan ünvana görə ayırır | indi |
| Lokal Mailpit | developer, saytın SMTP-si ora yönəlibsə | var |
| Sahibin domenində catch-all + IMAP | sayt "+" işarəsini qəbul etmirsə | indi |
| Saytın test API-si | "turbo": kontraktı olan saytlar | var |
| Pətəkin öz serverindəki qutu | sahib heç nə quraşdırmır | sonra, ödənişli modul: yalnız qəbul, bir gündən sonra silmə, yalnız run sahibinə görünür |

- Məktublar sahibin qutusunda qalır, `PLAN.md`-dəki "müştəri məlumatı Pətəkdən keçmir" prinsipi pozulmur.
- Sayt "+" işarəsini qəbul etmirsə, Pətək bunu tanıyır və hesabatda deyir.
- Hər B testerinin öz ünvanı var, yalnız o oxuyur. C testeri düzgün kodu oxuyub bilərəkdən səhv yazır.
- Telefon OTP ikinci növbədir: test API və ya hesabatda "bu saytda avtomatik qeydiyyat dayanır".

## 6. Kartlar — xırda xəta kataloqu

Prinsip: **kor naxış + ev xəritəsi.** Kəşfiyyatçı düymə və ya forma görəndə orkestrator uyğun kartı açır, görməyəndə
kart yoxdur. AI yalnız "bu düymə nədir?" sualında danışır. Böyük çöküşlər (5xx, boş səhifə, konsol xətası, qırılan axın)
hər kartda avtomatik yığılır.

Ümumi kataloq:

- iki klik eyni əməliyyatı iki dəfə yazır
- geri düyməsi köhnə formu yenidən göndərir
- səhifə yenilənəndə xəta mesajı itir, serverdə isə qalır
- mobil klaviatura təsdiq düyməsini örtür
- təsdiq linki təkrar işləyir və ya ilk dəfə 404 verir
- OTP boşluq və ya defislə yapışdırılanda qəbul olunmur
- login-dən sonra dərin link unudulur
- `https` və `www` fərqli sessiyadır
- məzmun gəlir, bildiriş sayı 0 qalır
- iki tab: birində çıxış, o birində hələ icazə görünür
- silinmiş obyektin URL-i 404 yox, boş 200 verir
- "yadda saxla" bir sahəni göndərmir
- e-poçtun böyük-kiçik hərfi ikinci hesab açır
- şifrə menecerinin doldurduğu sahə düyməni aktiv etmir

Sayt növünə görə:

| Növ | Nə sınır | İlk üç naxış | Əlavə kartlar |
|---|---|---|---|
| Mağaza | stok və pul | stok yarışı; səbətin login ilə birləşməsi; kuponun bir dəfə yanması | mini-səbət və checkout cəmi, miqdar dəyişəndə endirim, geri qayıdanda köhnə ünvan, filtr və sıralama, bitmiş məhsulda "Al", başqasının sifariş URL-i, mobil səbət düyməsi |
| Xəbər | vaxt və sızma | dərc olunan hamıya çatsın; qaralama sızmasın; şərh ikiləşməsin | planlı dərc vaxtında, dərcdən sonra düzəliş və keş, manşet və CDN, şərh mətni HTML kimi icra olunmasın, pulsuz məqalə limiti, axtarışda "bakı"/"Baku", canlı lent, bildiriş zəngi |
| Vitrin | link və dil | ölü link və düymə; dil güzgüləri arasında fərq; boş siyahı | forma validasiyası, yükləmə linki həqiqətən faylı verir, dərin link bir dildə 404, mobil CTA örtüyü |
| Şəxsiyyət və giriş sistemi | — | — | bölmə 7 |

Ödəniş: real kart və real POS heç vaxt. Stage-də ödəniş provayderinin test rejimi və onun test kartları ilə.
"Ödə"-dən sonra geri düyməsi ikinci ödəniş yaratmamalıdır.

## 7. Mühit, icazə və dürüst sərhədlər

| Hədəf | Pətək nə edir |
|---|---|
| Pre və ya stage: sahiblik təsdiqlənib, testləri aparan adam icazə verib | bütün testlər tam: yazma, qeydiyyat, OTP, ödənişin test rejimi |
| Sahiblik təsdiqlənməyib və ya icazə yoxdur | kəşfiyyat yalnız oxuyur |
| Localhost və daxili şəbəkə | sahiblik təsdiqi lazım deyil; icazə ilə tam test |
| Production hostu | qayda 8: yalnız `PETEK_ALLOW_PRODUCTION=true` ilə qəbul olunur, default rədd |
| Şəxsiyyət və vahid giriş sistemləri | test yalnız qurumun pre/stage mühitində, onun verdiyi unikal test kimlikləri ilə; real şəxsiyyət və real imza heç vaxt |

- **Sahiblik təsdiqi.** Sahib saytın kökünə Pətəkin verdiyi kodla kiçik fayl qoyur və ya DNS-ə qeyd yazır. Bu, bir
  dəfə edilir və yadda qalır. Beləliklə heç kim Pətəki başqasının saytına yönəldib orada hesab aça bilməz.
- **Hər testerə ayrı IP.** Paneldə seçimdir və yalnız sahibliyi təsdiqlənmiş saytda açılır. Seçilibsə hər tester ayrı
  IP-dən gəlir, sayt onları ayrı istifadəçi kimi görür. IP mənbəyini sahib verir: öz proxy ünvanları və ya bir neçə
  maşın. Playwright hər brauzerə ayrı proxy verə bildiyi üçün yeni kitabxana lazım deyil. Hər testerə IP çatmırsa,
  panel bunu başlamazdan əvvəl deyir.
- **Seçim yoxdursa.** Pətək "çox sorğu" cavabını tanıyır və tapıntını səbəbi ilə "alət boşluğu" rəfinə yazır: bütün
  testerlər bir IP-dən gəldi. Testerlərin başlanğıcı zamana yayılır, stage-də test IP-sinin limitdən azad edilməsi
  tövsiyə olunur. Hesabata qeyd düşür: bir ofisdən girən real istifadəçilər də IP limitinə ilişə bilər.
- **Dürüst dayanma.** CAPTCHA, SMS, admin təsdiqi, yalnız korporativ domenə e-poçt, dəvət və ya WAF qapını bağlayırsa,
  alət susmur və 20 dəfə təkrar etmir. Deyir: "qapını aça bilmədim, səbəb X; hesab ver və ya poçtu buraya yönəlt".
- **Run sonu.** "N təzə hesab qaldı, silməyə icazə?" İcazə və ya imkan yoxdursa, hesabatda qırmızı banner.
- **Tutum.** "Neçə nəfər" sualını alət cavablayır: canlı tester sayı, yaddaş, təxmini vaxt və AI xərci. Artıq tester
  dalğalara bölünür, canlı (realtime) kartlar yalnız eyni dalğadakılara verilir.

## 8. Hesabat

Hesabat həm ətraflı, həm sadədir. İki qat:

- **Üst qat, müştəri üçün.** Bir səhifə, saytın öz dilində qısa cümlələr: "kupon iki hesabda yandı", "qaralama anonimə
  tam mətn verdi". Texniki söz yoxdur.
- **Alt qat, hər tapıntının detalı.** Addımlar, screenshot, DOM, şəbəkə, vaxt, hansı hesab və qapı, "bu addımı təkrarla".

Hər tapıntı üç rəfdən birinə düşür: **sayt xətası**, **alət boşluğu**, **ssenari səhvi**. Müştəri birincini oxuyur,
developer üçüncünü düzəldir. Developer üçün eyni run-dan JUnit və SARIF çıxır. Kəşfiyyatçı düyməni saytın dilində
axtarır, hesabat sahibin dilində yazılır.

## 9. Pətəkdə bu gün nə var (kod və `PLAN.md`, 2026-09-26)

| İdeya | Bu gün | Planda |
|---|---|---|
| İki keçidli kəşfiyyat | kəşfiyyatçı üç fazalıdır: anonim, rolla, zərərsiz sınaq toxunuşu; sayt modeli `observed`/`inferred` | — |
| Kəşfiyyatçının saytın növünü təyin etməsi | yoxdur | yoxdur |
| Kəşfiyyatçının öz hesabı, özü qeydiyyat | yoxdur; kəşfiyyatçı test API ilə yaradılan şirkətin rol sessiyaları ilə girir | Faza 10: giriş zənciri `test_company` → `own_accounts` → `self_register` → `anonymous` |
| Təlimat sənədindəki test hesabları (A) | kəşfiyyatçıya düz dildə təlimat verilir, hesab götürmək yoxdur | Faza 10: panel, `.env` referansı |
| Testini bitirənlər arasında hesab dəyişdirmə (icazə ilə) | yoxdur | yoxdur |
| Ssenaridə qapı təyinatı (qeydiyyat, login, qonaq) | qismən: KadroHR qeydiyyat rejimləri (şirkət kodu, dəvət) | Faza 13: rejimlər sərbəst sətir olur |
| Sahibin qutusu + artı ünvan (IMAP) | Mailpit və test API oxunur | Faza 10: `imap` |
| Pətəkin serverindəki poçt qutusu | yoxdur | yoxdur (ödənişli modul, sonra) |
| Qapını bir dəfə öyrən, kodla təkrarla; baryer | yoxdur | yoxdur |
| Kəşfiyyatçının run boyu davam edib növbəti ssenarini genişləndirməsi | qismən: kəşfiyyat və run ayrı işlərdir; triaj run-dan sonra ssenari v2-ni diff kimi təklif edir | yoxdur |
| Testerlər bir-birini görmür; dəyər kartda yer tutucu ilə | qismən: `{self.*}`, `{vars.*}`, `{shared.*}` yer tutucuları var, amma testerə həmkar siyahısı (ad, e-poçt, rol) verilir | yoxdur |
| Xırda xəta kataloqu | `TestPatterns` başlanğıcı | Faza 13 kor naxışlar |
| Sayt növünə görə kartlar | yoxdur | yoxdur |
| Sahiblik təsdiqi | yoxdur | yoxdur |
| İcazədən sonra tam test | qismən: sınaq toxunuşu icazə ilə, production siyasəti (`PETEK_ALLOW_PRODUCTION`), `TargetReachability` | — |
| Hər testerə ayrı IP | yoxdur; testerlərin başlanğıcı zamana yayılır (`start_stagger_ms`) | yoxdur |
| Üç rəfli triaj | var | — |
| İki qatlı hesabat, JUnit/SARIF | HTML hesabat var; JUnit/SARIF yoxdur | yoxdur |
| Tutum | `capacity` əmri var | dalğalar yoxdur |
| Backend kiti | yoxdur | Faza 14 kontrakt kitləri |
| Demo: Ghost və WooCommerce sahibin serverində | fake target yalnız e2e üçündür | Faza 13: ikinci fake sayt (e2e üçün qalır) |

İdeyaların təxminən yarısı planın davamıdır. Tam yeni olanlar: saytın növünü təyin etmək, sahiblik təsdiqi, artı ünvanlı
poçt, qapını bir dəfə öyrənib kodla təkrarlamaq və baryer, kart yer tutucuları ilə tam izolyasiya, icazə ilə hesab
dəyişdirmə, run boyu davam edən kəşfiyyat, sayt növünə görə kartlar, hər testerə ayrı IP, iki qatlı hesabat, JUnit/SARIF,
dalğalar.

## 10. Tikinti sırası (təklif)

1. Sahiblik təsdiqi və icazə qapısı, çünki tam test ondan asılıdır
2. Poçt: sahibin qutusu + artı ünvan (IMAP); lokal Mailpit artıq var
3. Keçid 0 → 1 kəşfiyyatçı: saytın növü, öz hesabı, özü qeydiyyat
4. Qapı təyinatı, unikal hesablar, qapı baryeri, kart yer tutucuları, icazə ilə hesab dəyişdirmə
5. Xırda xəta kartları: mağaza və xəbər üçün ilk üç naxış
6. İki qatlı, üç rəfli hesabat, JUnit/SARIF
7. Tutum, dalğalar və "hər testerə ayrı IP"
8. Demo: Ghost və WooCommerce sahibin serverində

## 11. Suallar — hamısı bağlanıb (2026-09-26)

| Sual | Qərar |
|---|---|
| 1. Hesab çatmayanda | icazə ilə, testini bitirənlər arasında hesab dəyişdirmə (bənd 4) |
| 2. Hesabsız tester | qonaq qapısı; "bir tester, bir mühit; hesab varsa eyni anda yalnız onda" (bənd 7) |
| 3. Həmkar siyahısı | götürülür; başqa testerə aid dəyər kartda yer tutucu ilə (bənd 11) |
| 4. Kəşfiyyatçının hesabı | təlimatdakı öz hesabı və ya özü açdığı hesab; testerlə paylaşılmır (bənd 12) |
| 5. Canlı sayt | pre və ya stage, icazədən sonra tam test (bənd 9) |
| 6. Saytın növünü kim təyin edir | kəşfiyyatçı (bənd 2) |
| 7. Sahiblik | fayl və ya DNS təsdiqi; localhost və daxili şəbəkə istisna (bənd 16) |
| 8. Tək IP | "hər testerə ayrı IP" seçimi; seçilməyibsə "alət boşluğu" rəfi (bənd 13) |
| 9. Pətəkin serverindəki poçt qutusu | əvvəl sahibin qutusu + artı ünvan; server sonra, ödənişli (bənd 14) |
| 10. Demo sayt | Ghost və WooCommerce sahibin serverində (bənd 15) |
| N1. Təlimatda parol | yalnız test hesabları, istifadə qaydalarında yazılır; kod parolu `Secret` saxlayır (bənd 5, bölmə 4) |
| N2. Run zamanı kəşfiyyat | proses bitənə qədər davam edir, növbəti run-ın ssenarisini genişləndirir (bənd 8) |
| N3. Hesab növbəsi | testi aparan adamın icazəsi ilə, testini bitirənlər arasında (bənd 4) |
