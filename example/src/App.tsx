import { useState } from 'react';
import {
  Image,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  NFCSDK,
  NFCSDKError,
  type NFCErrorEvent,
  type NFCProgressEvent,
  type NFCScanResult,
} from 'react-native-nitro-nfc';

export default function App() {
  const [citizenId, setCitizenId] = useState('');
  const [result, setResult] = useState<NFCScanResult | null>(null);
  const [progress, setProgress] = useState<NFCProgressEvent>({
    progress: 0,
    message: 'Nhập CCCD rồi bấm nút để mở NFC',
  });
  const error: NFCErrorEvent | undefined =
    'error' in progress ? (progress.error as NFCErrorEvent) : undefined;

  const available = NFCSDK.isAvailable();

  const handleStart = async () => {
    setResult(null);
    setProgress({
      progress: 0,
      message: 'Đang mở màn hình NFC native...',
    });

    try {
      const data = await NFCSDK.scan({
        citizenId,
        onProgress: (event) => {
          console.log('[NitroNfc] Progress:', event);
          setProgress(event);
        },
      });

      console.log('[NitroNfc] Result:', data);
      setResult(data);
      setProgress({
        progress: 100,
        message: 'Đã nhận metadata NFC từ native module',
      });
    } catch (caughtError) {
      const message =
        caughtError instanceof NFCSDKError
          ? caughtError.message
          : 'Không thể khởi động Nitro NFC.';

      setProgress({
        progress: -1,
        message,
      });
    }
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.titleBadge}>
          <Text style={styles.title}>react-native-nfc-nitro</Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>CCCD / Số giấy tờ</Text>
          <TextInput
            value={citizenId}
            onChangeText={setCitizenId}
            placeholder="Nhập số CCCD"
            placeholderTextColor="#A0A0B0"
            keyboardType="number-pad"
            style={styles.input}
          />

          <Pressable style={styles.button} onPress={handleStart}>
            <Text style={styles.buttonText}>Mở NFC</Text>
          </Pressable>
        </View>

        {result && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Thông tin CCCD</Text>

            {result.chipImageUri ? (
              <Image
                source={{
                  uri: result.chipImageUri,
                }}
                style={styles.chipImage}
                resizeMode="contain"
              />
            ) : null}

            {[
              { label: 'Số CCCD', value: result.citizenId },
              { label: 'Họ và tên', value: result.fullName },
              { label: 'Ngày sinh', value: result.dob },
              { label: 'Giới tính', value: result.gender },
              { label: 'Quốc tịch', value: result.nationality },
              { label: 'Địa chỉ', value: result.permanentAddress },
              { label: 'Ngày cấp', value: result.issueDate },
              { label: 'Ngày hết hạn', value: result.expireDate },
            ].map((item) => (
              <View key={item.label} style={styles.resultRow}>
                <Text style={styles.resultKey}>{item.label}</Text>
                <Text style={styles.resultValue} selectable>
                  {item.value || '(trống)'}
                </Text>
              </View>
            ))}
          </View>
        )}

        {result && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Dữ liệu chip (lazy)</Text>
            <ScrollView style={styles.resultBox}>
              {[
                { label: 'IMAGE', value: result.imageFromChipSize },
                { label: 'DG1', value: result.dg1Size },
                { label: 'DG2', value: result.dg2Size },
                { label: 'DG13', value: result.dg13Size },
                { label: 'DG14', value: result.dg14Size },
                { label: 'SOD', value: result.sodSize },
              ].map((item) => (
                <View key={item.label} style={styles.resultRow}>
                  <Text style={styles.resultKey}>{item.label}</Text>
                  <Text style={styles.resultValue} selectable>
                    {item.value > 0 ? `${item.value} bytes` : '(trống)'}
                  </Text>
                </View>
              ))}
            </ScrollView>
          </View>
        )}

        {!result && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Kết quả NFC</Text>
            <Text style={styles.resultText}>Chưa có dữ liệu từ native.</Text>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#F5F5FA',
  },
  container: {
    paddingHorizontal: 20,
    paddingTop: 32,
    paddingBottom: 40,
  },
  eyebrow: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 2,
    textTransform: 'uppercase',
    color: '#5E5CE6',
    marginBottom: 6,
  },
  titleBadge: {
    alignSelf: 'center',
    backgroundColor: '#EEEDFE',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 10,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: '#D6D4F8',
    justifyContent: 'center',
    alignItems: 'center',
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: '#4A48C9',
    fontFamily: 'Courier',
    letterSpacing: -0.3,
  },
  description: {
    fontSize: 15,
    lineHeight: 22,
    color: '#8C8CA1',
    marginBottom: 28,
  },
  card: {
    backgroundColor: '#FFFFFF',
    borderRadius: 20,
    padding: 20,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#ECECF0',
    shadowColor: '#5E5CE6',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.04,
    shadowRadius: 12,
    elevation: 3,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#1C1C2E',
    marginBottom: 14,
    letterSpacing: 0.2,
  },
  chipImage: {
    width: 110,
    height: 140,
    borderRadius: 14,
    alignSelf: 'center',
    marginBottom: 20,
    backgroundColor: '#F0F0F6',
    borderWidth: 2,
    borderColor: '#E0DFF8',
  },
  input: {
    borderWidth: 1,
    borderColor: '#DDDDE6',
    borderRadius: 14,
    backgroundColor: '#FAFAFC',
    paddingHorizontal: 16,
    paddingVertical: 14,
    fontSize: 16,
    color: '#1C1C2E',
    marginBottom: 14,
  },
  button: {
    backgroundColor: '#5E5CE6',
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    shadowColor: '#5E5CE6',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 10,
    elevation: 5,
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 17,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  statusText: {
    fontSize: 14,
    color: '#6E6E82',
    marginBottom: 6,
  },
  messageText: {
    fontSize: 15,
    color: '#1C1C2E',
    marginBottom: 12,
  },
  debugText: {
    fontSize: 13,
    color: '#8C8CA1',
  },
  resultBox: {
    maxHeight: 500,
  },
  resultRow: {
    marginBottom: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#ECECF0',
    paddingBottom: 10,
    paddingLeft: 10,
    borderLeftWidth: 2,
    borderLeftColor: '#5E5CE6',
  },
  resultKey: {
    fontSize: 11,
    fontWeight: '700',
    color: '#5E5CE6',
    marginBottom: 3,
    textTransform: 'uppercase',
    letterSpacing: 1,
  },
  resultValue: {
    fontSize: 15,
    color: '#1C1C2E',
    lineHeight: 21,
  },
  resultText: {
    fontSize: 15,
    lineHeight: 21,
    color: '#8C8CA1',
  },
});

