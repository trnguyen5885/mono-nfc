import 'dart:async';

import 'package:flutter/material.dart';
import 'package:identity_nfc/identity_nfc.dart';

void main() => runApp(const IdentityNfcExampleApp());

class IdentityNfcExampleApp extends StatelessWidget {
  const IdentityNfcExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Identity NFC example',
        theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
        home: const IdentityNfcExamplePage(),
      );
}

class IdentityNfcExamplePage extends StatefulWidget {
  const IdentityNfcExamplePage({super.key});

  @override
  State<IdentityNfcExamplePage> createState() => _IdentityNfcExamplePageState();
}

class _IdentityNfcExamplePageState extends State<IdentityNfcExamplePage> {
  final _citizenIdController = TextEditingController();
  StreamSubscription<IdentityNfcProgress>? _progressSubscription;
  IdentityNfcProgress? _progress;
  IdentityNfcScanResult? _result;
  String? _error;
  bool _available = false;
  bool _reading = false;
  bool _readImage = true;
  String _language = 'en';

  @override
  void initState() {
    super.initState();
    _progressSubscription = IdentityNfc.progress.listen((event) {
      if (mounted) setState(() => _progress = event);
    });
    _checkAvailability();
  }

  @override
  void dispose() {
    _progressSubscription?.cancel();
    _citizenIdController.dispose();
    super.dispose();
  }

  Future<void> _checkAvailability() async {
    try {
      final available = await IdentityNfc.isAvailable();
      if (mounted) setState(() => _available = available);
    } on IdentityNfcException catch (error) {
      if (mounted) setState(() => _error = error.message);
    }
  }

  Future<void> _scan() async {
    setState(() {
      _reading = true;
      _result = null;
      _error = null;
      _progress = null;
    });
    try {
      final result = await IdentityNfc.scan(IdentityNfcScanRequest(
        citizenId: _citizenIdController.text,
        readImage: _readImage,
        language: _language,
      ));
      if (mounted) setState(() => _result = result);
    } on IdentityNfcException catch (error) {
      if (mounted) setState(() => _error = '${error.code}: ${error.message}');
    } finally {
      if (mounted) setState(() => _reading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Identity NFC example')),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: <Widget>[
              Text(
                _available ? 'NFC is available' : 'NFC is unavailable or disabled',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _citizenIdController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'Citizen ID',
                ),
              ),
              SwitchListTile(
                value: _readImage,
                onChanged: _reading ? null : (value) => setState(() => _readImage = value),
                title: const Text('Read portrait image'),
              ),
              DropdownButtonFormField<String>(
                value: _language,
                decoration: const InputDecoration(labelText: 'Language'),
                items: const <DropdownMenuItem<String>>[
                  DropdownMenuItem(value: 'en', child: Text('English')),
                  DropdownMenuItem(value: 'vi', child: Text('Tiếng Việt')),
                ],
                onChanged: _reading
                    ? null
                    : (value) => setState(() => _language = value ?? 'en'),
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _available && !_reading ? _scan : null,
                child: Text(_reading ? 'Scanning…' : 'Start NFC scan'),
              ),
              if (_progress != null) ...<Widget>[
                const SizedBox(height: 24),
                LinearProgressIndicator(value: (_progress!.progress / 100).clamp(0, 1)),
                const SizedBox(height: 8),
                Text('${_progress!.progress.toStringAsFixed(0)}% — ${_progress!.message}'),
              ],
              if (_error != null) ...<Widget>[
                const SizedBox(height: 24),
                Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
              if (_result != null) ...<Widget>[
                const SizedBox(height: 24),
                Text('Scan result', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                Text('Name: ${_result!.fullName}'),
                Text('Date of birth: ${_result!.dob}'),
                Text('Gender: ${_result!.gender}'),
                Text('Nationality: ${_result!.nationality}'),
                Text('DG1 bytes: ${_result!.dg1Data.length}'),
                Text('DG2 bytes: ${_result!.dg2Data.length}'),
                Text('Portrait bytes: ${_result!.imageFromChipData.length}'),
              ],
            ],
          ),
        ),
      );
}
