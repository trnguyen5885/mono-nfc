# Tích hợp NFCSDK vào React Native

Hướng dẫn này dành cho ứng dụng React Native tiêu thụ package đã phát hành
`react-native-nitro-nfc`. Package mở native NFC UI, còn Android/iOS core đọc
chip, PACE, parse dữ liệu và quản lý cache ngắn hạn.

## 1. Điều kiện trước khi tích hợp

- React Native bật New Architecture và tương thích với
  `react-native-nitro-modules`.
- Android: thiết bị thật có NFC, Android API 24 trở lên và NFC đang bật.
- iOS: thiết bị thật iOS 15 trở lên, Apple Developer team được cấp quyền NFC
  Tag Reading.
- Artifact `nfc-core` (Maven/AAR) và `NFCCore` CocoaPods pod (hiện là source
  pod) phải đã được SDK distributor publish ở repository mà ứng dụng host có
  thể truy cập.

Emulator Android và iOS Simulator không thể thay thế kiểm thử chip NFC thật.

## 2. Cài package

Từ app React Native:

```sh
npm install react-native-nitro-nfc react-native-nitro-modules
# hoặc
yarn add react-native-nitro-nfc react-native-nitro-modules
```

Sau khi cài dependency native, phải rebuild app; chỉ reload Metro là không đủ.

```sh
cd ios && pod install && cd ..
npx react-native run-android
# hoặc chạy iOS trên thiết bị thật
npx react-native run-ios --device
```

Không cần đăng ký module thủ công. React Native autolinking và Nitro
autolinking đăng ký package trong native build.

## 3. Cấu hình Android

Package đã khai báo `android.permission.NFC`, Activity quét NFC và
`android.permission.VIBRATE`; manifest merger sẽ đưa chúng vào app host.

App host vẫn phải dùng `minSdk` tối thiểu 24:

```gradle
android {
  defaultConfig {
    minSdk 24
  }
}
```

Khi dùng artifact production, Gradle phải biết Maven repository chứa
`com.identity.nfc:nfc-core`. URL và credentials do SDK distributor cung cấp;
không thêm local Gradle project `:nfc-core` vào app production.

Trước khi gọi scan, người dùng cần bật NFC. `NFCSDK.isAvailable()` trả `false`
khi không có adapter hoặc native module không khả dụng. Khi adapter có nhưng
NFC tắt, scan trả lỗi `NFCDisabled`.

## 4. Cấu hình iOS

Trong Xcode, bật capability **Near Field Communication Tag Reading** cho app
target. Xcode sẽ tạo entitlement phù hợp. Host cũng cần có usage description
trong `ios/<App>/Info.plist`:

```xml
<key>NFCReaderUsageDescription</key>
<string>Ứng dụng cần NFC để đọc chip trên thẻ định danh.</string>
```

Entitlement tối thiểu cho luồng tag reader:

```xml
<key>com.apple.developer.nfc.readersession.formats</key>
<array>
  <string>TAG</string>
</array>
```

Các ISO 7816 application identifier bổ sung phụ thuộc loại thẻ và Apple
Developer configuration của tổ chức. Chỉ thêm khi Apple entitlement profile
của app cho phép. Sau mọi thay đổi Pod/capability, chạy lại:

```sh
cd ios
pod install
```

### OpenSSL

Mặc định `NFCCore` tự kéo `OpenSSL-Universal`. Đây là mode nên dùng cho app
mới. Không thêm OpenSSL binary thứ hai vào host.

Chỉ khi host đã quản lý một OpenSSL tương thích, dùng manual mode trước lúc
`pod install`:

```sh
NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install
```

Trong mode này host phải tự khai báo provider của mình trong Podfile và provider
đó phải expose Swift module tên `OpenSSL`. `NFCSDK_USE_MANUAL_OPENSSL=1` vẫn là
alias tương thích cũ. Không bật manual mode nếu host chưa cung cấp OpenSSL.

## 5. Luồng scan khuyến nghị

Gọi `isAvailable()` trước. Đăng ký callback progress ngay trong `scan()` để
tránh duplicate event từ global listener. Native Android tự mở bottom-sheet
scan; iOS mở CoreNFC system sheet.

```tsx
import { NFCSDK, NFCSDKError } from 'react-native-nitro-nfc';

export async function scanCitizenCard(citizenId: string) {
  if (!NFCSDK.isAvailable()) {
    throw new Error('Thiết bị không có NFC hoặc NFC chưa sẵn sàng.');
  }

  try {
    return await NFCSDK.scan({
      citizenId,
      readImage: true,
      cachePolicy: 'fresh',
      language: 'vi',
      onProgress: (event) => {
        if ('error' in event) {
          // Chỉ hiển thị mã/lỗi; không ghi dữ liệu chip vào log.
          console.warn(event.error.code, event.error.message);
          return;
        }
        // Cập nhật UI progress của app từ event.progress và event.message.
      },
    });
  } catch (error) {
    if (error instanceof NFCSDKError) {
      // Ví dụ: InvalidCitizenId, NFCDisabled, PACEError, UserCanceled.
      throw error;
    }
    throw error;
  }
}
```

`citizenId` phải chỉ chứa chữ số và dài ít nhất 6 ký tự. Với CCCD Việt Nam,
thường dùng 12 chữ số; core hiện dùng 6 chữ số cuối làm CAN key.

`readImage: false` bỏ qua DG2/portrait để giảm thời gian đọc và bộ nhớ.
`cachePolicy: 'reuse-if-valid'` chỉ tái sử dụng DG2 trong cache native ngắn hạn
khi fingerprint DG1/SOD trùng; DG1 và SOD vẫn được đọc lại từ chip.

## 6. Kết quả, binary data và cleanup

`scan()` trả metadata nhẹ cùng URI ảnh cache và kích thước các data group. Raw
DG và ảnh không đi qua JavaScript mặc định. Lấy dữ liệu chỉ khi thực sự cần:

```ts
const result = await scanCitizenCard('001234567890');

const dg1 = NFCSDK.getDataGroupBuffer('DG1');
if (dg1) {
  const bytes = new Uint8Array(dg1);
  // Xử lý bytes trong phạm vi tối thiểu cần thiết.
}

// Xóa result, ảnh cache và cache DG2 khi flow nghiệp vụ kết thúc.
NFCSDK.clearCachedScan();
```

Tên data group được hỗ trợ: `IMAGE`, `DG1`, `DG2`, `DG13`, `DG14`, `SOD`.
Ưu tiên `getDataGroupBuffer()` thay vì Base64 để tránh tăng dung lượng/bộ nhớ.

## 7. Xử lý lỗi và UX

| Code | Cách xử lý UX khuyến nghị |
| --- | --- |
| `NFCNotSupported` | Thông báo thiết bị không hỗ trợ NFC. |
| `NFCDisabled` | Hướng dẫn bật NFC rồi retry. |
| `InvalidCitizenId` | Yêu cầu nhập lại số định danh. |
| `UserCanceled` | Đóng flow nhẹ nhàng, không coi là lỗi hệ thống. |
| `InvalidMRZKey`, `PACEError` | Kiểm tra đúng CAN/CCCD và thử lại. |
| `ConnectionError`, `SessionTimeout` | Giữ thẻ ổn định, tháo ốp dày và thử lại. |
| `ScanInProgress` | Disable nút bắt đầu scan cho đến khi Promise hoàn tất. |

Một error progress event có thể được gửi trước khi Promise reject. Chỉ xử lý
một nguồn event cho mỗi UI state để không hiện lỗi hai lần.

## 8. Checklist trước production

- [ ] Kiểm thử trên device Android và iPhone thật với card fixture được phép.
- [ ] Không log hoặc lưu DG bytes, ảnh chip, CAN/CCCD ngoài nhu cầu nghiệp vụ.
- [ ] Gọi `clearCachedScan()` sau khi flow hoàn tất.
- [ ] Xác nhận entitlement iOS có trong provisioning profile release.
- [ ] Xác nhận repository Maven/Pods và version native core được pin theo
      release của `react-native-nitro-nfc`.
- [ ] Kiểm thử success, cancel, NFC disabled, tag lost, retry và `readImage: false`.

Xem thêm [architecture và parity matrix](../architecture/PARITY_CHECKS.md).
