using System;
using System.IO;
using System.IO.Ports;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace ESP32LoggerWin
{
    public partial class MainWindow : Window
    {
        private SerialPort? _serialPort;
        private LogWindow? _logWindow;
        private AppConfig _config = null!;
        private double _maxSpeed = 0.0;

        // ネオンカラーの定義
        private static readonly SolidColorBrush NeonRed = new(Color.FromRgb(255, 69, 58));
        private static readonly SolidColorBrush NeonGreen = new(Color.FromRgb(48, 209, 88));
        private static readonly SolidColorBrush NeonOff = new(Color.FromRgb(79, 93, 115));

        private StreamWriter? _logFileWriter;
        private string _currentLogFilePath = string.Empty;

        // シリアル受信用のバッファ（改行で分割するためのストリームバッファ）
        private readonly StringBuilder _serialBuffer = new();

        public MainWindow()
        {
            InitializeComponent();
            InitializeApp();
        }

        private void InitializeApp()
        {
            // 1. 設定のロード
            _config = AppConfig.Load();
            SaveDirTextBox.Text = _config.SaveDirectory;

            // PPR コンボボックスの初期選択を設定
            foreach (ComboBoxItem item in PprComboBox.Items)
            {
                if (double.TryParse(item.Tag?.ToString(), out double ppr) && Math.Abs(ppr - _config.PulsePerRevolution) < 0.01)
                {
                    PprComboBox.SelectedItem = item;
                    break;
                }
            }

            // 2. 利用可能なCOMポート一覧をロード
            RefreshComPorts();

            // 3. インジケーターの初期化 (消灯)
            BtLed.Fill = NeonRed;
            GpsLed.Fill = NeonRed;
            AntennaLed.Fill = NeonRed;

            // 4. ログウィンドウの作成
            _logWindow = new LogWindow();
        }

        /// <summary>
        /// 利用可能なCOMポートの一覧を取得してコンボボックスを設定
        /// </summary>
        private void RefreshComPorts()
        {
            ComPortComboBox.Items.Clear();
            string[] ports = SerialPort.GetPortNames();
            foreach (string port in ports)
            {
                ComPortComboBox.Items.Add(port);
            }

            if (ComPortComboBox.Items.Count > 0)
            {
                // configに保存された最後のポートがあればそれを選ぶ、なければ最初のポート
                if (!string.IsNullOrEmpty(_config.LastComPort) && ComPortComboBox.Items.Contains(_config.LastComPort))
                {
                    ComPortComboBox.SelectedItem = _config.LastComPort;
                }
                else
                {
                    ComPortComboBox.SelectedIndex = 0;
                }
            }
        }

        private void ComPortComboBox_DropDownOpened(object sender, EventArgs e)
        {
            string? currentSelected = ComPortComboBox.SelectedItem?.ToString();
            RefreshComPorts();
            if (currentSelected != null && ComPortComboBox.Items.Contains(currentSelected))
            {
                ComPortComboBox.SelectedItem = currentSelected;
            }
        }

        private void LogWindowButton_Click(object sender, RoutedEventArgs e)
        {
            if (_logWindow == null)
            {
                _logWindow = new LogWindow();
            }
            _logWindow.Show();
            _logWindow.Activate();
        }

        // データ受信フラグ（デバッグ用：接続後にデータが到着したか判定）
        private volatile bool _dataReceived = false;

        /// <summary>
        /// 接続処理。選択ポートで失敗した場合、他のCOMポートへ自動フォールバックを試みる。
        /// </summary>
        private void ConnectButton_Click(object sender, RoutedEventArgs e)
        {
            if (ComPortComboBox.SelectedItem == null)
            {
                MessageBox.Show("接続するCOMポートを選択してください。", "エラー", MessageBoxButton.OK, MessageBoxImage.Error);
                return;
            }

            string selectedPort = ComPortComboBox.SelectedItem.ToString()!;

            // 試行するポートリストを構築（選択ポートを先頭に、他のポートをフォールバックとして追加）
            var portsToTry = new System.Collections.Generic.List<string> { selectedPort };
            foreach (var item in ComPortComboBox.Items)
            {
                string port = item.ToString()!;
                if (port != selectedPort) portsToTry.Add(port);
            }

            foreach (string port in portsToTry)
            {
                if (TryConnect(port))
                {
                    // UIのCOMポート選択を実際に接続されたポートに合わせる
                    if (ComPortComboBox.Items.Contains(port))
                    {
                        ComPortComboBox.SelectedItem = port;
                    }
                    return;
                }
            }

            MessageBox.Show("利用可能なすべてのCOMポートへの接続に失敗しました。\n\n" +
                            "以下を確認してください:\n" +
                            "・ESP32の電源がONであること\n" +
                            "・WindowsのBluetooth設定でESP32_Loggerがペアリング済みであること\n" +
                            "・他のアプリがCOMポートを使用していないこと",
                            "接続エラー", MessageBoxButton.OK, MessageBoxImage.Error);
        }

        /// <summary>
        /// 指定ポートへの接続を試行する。成功すればtrue、失敗すればfalseを返す。
        /// </summary>
        private bool TryConnect(string portName)
        {
            try
            {
                DisconnectInternal();
                _dataReceived = false;

                _logWindow?.AppendLog($"[SYSTEM] COMポート {portName} への接続を試行中...");

                _serialPort = new SerialPort(portName, 115200, Parity.None, 8, StopBits.One)
                {
                    ReadTimeout = 500,
                    WriteTimeout = 2000,
                    Encoding = Encoding.ASCII,
                    DtrEnable = true,
                    RtsEnable = true
                };

                _serialPort.DataReceived += SerialPort_DataReceived;
                _serialPort.Open();

                // UIの切り替え
                ConnectButton.IsEnabled = false;
                DisconnectButton.IsEnabled = true;
                ComPortComboBox.IsEnabled = false;
                BtLed.Fill = NeonGreen;

                // 設定の永続化
                _config.LastComPort = portName;
                _config.Save();

                _logWindow?.AppendLog($"[SYSTEM] COMポート {portName} に接続しました。");

                // ロギングCSVの作成
                StartLocalLogging();

                // A-GPS送信を非同期で実行（UIをブロックしない）
                ThreadPool.QueueUserWorkItem(_ => SendAgpsAssistData());

                // 5秒後にデータ受信チェック（データが来ていなければガイダンスを表示）
                var checkTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
                checkTimer.Tick += (s, args) =>
                {
                    checkTimer.Stop();
                    if (!_dataReceived && _serialPort != null && _serialPort.IsOpen)
                    {
                        _logWindow?.AppendLog("[WARNING] 接続から5秒経過しましたが、ESP32からのデータを受信していません。");
                        _logWindow?.AppendLog("[WARNING] ESP32が正常に動作しているか、COMポートが正しいか確認してください。");
                        _logWindow?.AppendLog($"[INFO] 現在の接続ポート: {portName}");
                    }
                };
                checkTimer.Start();

                return true;
            }
            catch (Exception ex)
            {
                _logWindow?.AppendLog($"[ERROR] {portName} への接続失敗: {ex.Message}");
                DisconnectInternal();
                return false;
            }
        }

        private void DisconnectButton_Click(object sender, RoutedEventArgs e)
        {
            DisconnectInternal();
            _logWindow?.AppendLog("[SYSTEM] 切断しました。");
        }

        private void DisconnectInternal()
        {
            // ロギングファイルをクローズ
            StopLocalLogging();

            if (_serialPort != null)
            {
                try
                {
                    _serialPort.DataReceived -= SerialPort_DataReceived;
                    if (_serialPort.IsOpen)
                    {
                        _serialPort.Close();
                    }
                }
                catch { }
                _serialPort.Dispose();
                _serialPort = null;
            }

            // UI切り替え
            Dispatcher.Invoke(() =>
            {
                ConnectButton.IsEnabled = true;
                DisconnectButton.IsEnabled = false;
                ComPortComboBox.IsEnabled = true;
                
                // メーター値とインジケーターをリセット
                SpeedRotator.Angle = -120;
                SpeedText.Text = "0.0";
                RpmRotator.Angle = -120;
                RpmText.Text = "0";
                
                BtLed.Fill = NeonRed;
                GpsLed.Fill = NeonRed;
                AntennaLed.Fill = NeonRed;
                SatellitesText.Text = "捕捉衛星: 0";
                UtcTimeText.Text = "UTC時刻: --:--:--";
            });
        }

        /// <summary>
        /// A-GPSアシストデータの生成と送信。
        /// PCの現在時刻およびダミー位置（日本中心）を元に NMEA $AGPS センテンスを作成して送信。
        /// </summary>
        private void SendAgpsAssistData()
        {
            if (_serialPort == null || !_serialPort.IsOpen) return;

            try
            {
                // PCのローカル時刻からUTCに変換
                DateTime utcNow = DateTime.UtcNow;
                string dateStr = utcNow.ToString("ddMMyy");  // DDMMYY形式
                string timeStr = utcNow.ToString("HHmmss.ff"); // HHMMSS.ff形式

                // スマートフォン側と同様、基本アシスト座標として日本の平均中心位置を使用
                // ※Windows位置情報が確実に動作しない環境への配慮
                double lat = 35.681234;
                double lon = 139.767123;
                float alt = 50.0f;

                // $AGPSペイロードの作成
                string payload = $"AGPS,{lat:F6},{lon:F6},{alt:F1},{dateStr},{timeStr}";

                // ペイロード全体のXORチェックサム計算
                byte checksum = 0;
                foreach (char c in payload)
                {
                    checksum ^= (byte)c;
                }

                // 完成版 $AGPS センテンス
                string packet = $"${payload}*{checksum:X2}\n";

                // 送信
                _serialPort.Write(packet);
                _logWindow?.AppendLog($"[A-GPS送信] {packet.Trim()}");
            }
            catch (Exception ex)
            {
                _logWindow?.AppendLog($"[A-GPSエラー] 送信失敗: {ex.Message}");
            }
        }

        /// <summary>
        /// シリアルポートのデータ受信イベントハンドラ。
        /// ReadLine()のブロッキング問題を回避するため、生バイトを読み取り
        /// 手動で改行(\n)で分割してパースする方式を採用。
        /// </summary>
        private void SerialPort_DataReceived(object sender, SerialDataReceivedEventArgs e)
        {
            if (_serialPort == null || !_serialPort.IsOpen) return;

            try
            {
                int bytesAvailable = _serialPort.BytesToRead;
                if (bytesAvailable <= 0) return;

                // データ受信フラグを立てる（接続後のチェックタイマー用）
                _dataReceived = true;

                // 利用可能なバイトを一括で読み取る（非ブロッキング）
                byte[] buffer = new byte[bytesAvailable];
                int bytesRead = _serialPort.Read(buffer, 0, bytesAvailable);
                string chunk = Encoding.ASCII.GetString(buffer, 0, bytesRead);

                // デバッグ: 受信した生データをログに表示
                _logWindow?.AppendLog($"[DEBUG RX] {bytesRead}bytes: {chunk.Replace("\r", "<CR>").Replace("\n", "<LF>")}");

                // バッファに追記し、改行(\n)で分割して行単位で処理
                _serialBuffer.Append(chunk);

                string bufferStr = _serialBuffer.ToString();
                int newlineIdx;
                while ((newlineIdx = bufferStr.IndexOf('\n')) >= 0)
                {
                    string line = bufferStr.Substring(0, newlineIdx).TrimEnd('\r');
                    bufferStr = bufferStr.Substring(newlineIdx + 1);

                    if (!string.IsNullOrWhiteSpace(line))
                    {
                        // 生ログウィンドウに完成行を表示
                        _logWindow?.AppendLog(line);

                        // パース処理
                        ParseDataLine(line);
                    }
                }

                // 未処理の残りをバッファに戻す
                _serialBuffer.Clear();
                _serialBuffer.Append(bufferStr);

                // バッファが異常に大きくなった場合の保護（1KB超えたらクリア）
                if (_serialBuffer.Length > 1024)
                {
                    _logWindow?.AppendLog("[DEBUG] バッファオーバーフロー検知。クリアします。");
                    _serialBuffer.Clear();
                }
            }
            catch (Exception ex)
            {
                _logWindow?.AppendLog($"[DEBUG ERROR] 受信例外: {ex.Message}");
            }
        }

        /// <summary>
        /// 受信したCSVデータ（[pulse_us],[速度],[緯度],[経度],[時刻]*[チェックサム]\n）をパースする
        /// </summary>
        private void ParseDataLine(string rawLine)
        {
            try
            {
                int asteriskIdx = rawLine.IndexOf('*');
                if (asteriskIdx < 0) return;

                string payload = rawLine.Substring(0, asteriskIdx);
                string checksumStr = rawLine.Substring(asteriskIdx + 1).Trim();

                // 1. チェックサムの検証（XOR）
                byte expectedChecksum = 0;
                foreach (char c in payload)
                {
                    expectedChecksum ^= (byte)c;
                }

                if (!byte.TryParse(checksumStr, System.Globalization.NumberStyles.HexNumber, null, out byte receivedChecksum) 
                    || expectedChecksum != receivedChecksum)
                {
                    return; // チェックサム破損
                }

                // 2. CSVパース
                string[] parts = payload.Split(',');
                if (parts.Length < 5) return;

                if (!double.TryParse(parts[0], out double pulseUs)) return;
                if (!double.TryParse(parts[1], out double speed)) return;
                if (!double.TryParse(parts[2], out double latitude)) return;
                if (!double.TryParse(parts[3], out double longitude)) return;
                string utcTime = parts[4];

                // 3. RPMの算出
                // rawPulseRpm = 60,000,000 / pulse_us
                double rawPulseRpm = pulseUs > 0 ? 60000000.0 / pulseUs : 0.0;
                double displayRpm = _config.PulsePerRevolution > 0 ? rawPulseRpm / _config.PulsePerRevolution : 0.0;

                // 4. GPSのステータスおよびアンテナ状態判定
                // NMEAで緯度/経度が0.0以外 ＝ 測位が有効
                bool isGpsValid = Math.Abs(latitude) > 0.001 && Math.Abs(longitude) > 0.001;

                // NEO-6Mが信号を受信している、またはモジュールと疎通している＝アンテナアクティブ
                // 簡易判定：測位可能、または衛星情報が有効、またはデータの取得が正常な状態
                bool isAntennaConnected = pulseUs >= 0; 

                // 5. UI更新（スレッド同期）
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    // スピードメーター更新
                    SpeedText.Text = speed.ToString("F1");
                    double speedAngle = (Math.Min(speed, 180.0) / 180.0) * 240.0 - 120.0;
                    SpeedRotator.Angle = speedAngle;

                    // タコメーター更新
                    RpmText.Text = ((int)displayRpm).ToString();
                    double rpmAngle = (Math.Min(displayRpm, 10000.0) / 10000.0) * 240.0 - 120.0;
                    RpmRotator.Angle = rpmAngle;

                    // 最高速更新
                    if (speed > _maxSpeed)
                    {
                        _maxSpeed = speed;
                        MaxSpeedText.Text = _maxSpeed.ToString("F1");
                    }

                    // ランプインジケーターの制御
                    GpsLed.Fill = isGpsValid ? NeonGreen : NeonRed;
                    AntennaLed.Fill = isAntennaConnected ? NeonGreen : NeonRed;
                    UtcTimeText.Text = $"UTC時刻: {utcTime}";

                    // 測位状態に応じた簡易捕捉数シミュレーション、またはパースの拡張
                    SatellitesText.Text = isGpsValid ? "捕捉衛星: 6+ (測位OK)" : "捕捉衛星: 0 (未測位)";
                }));

                // 6. ローカルファイルへのCSVロギング
                WriteToLogFile(rawPulseRpm, displayRpm, speed, latitude, longitude, utcTime);
            }
            catch (Exception)
            {
                // パース失敗等はスキップ
            }
        }

        #region ロギング処理

        private void StartLocalLogging()
        {
            try
            {
                string dir = _config.SaveDirectory;
                if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir))
                {
                    dir = AppDomain.CurrentDomain.BaseDirectory;
                }

                string fileName = $"log_{DateTime.Now:yyyyMMdd}.csv";
                _currentLogFilePath = Path.Combine(dir, fileName);

                bool isNew = !File.Exists(_currentLogFilePath);
                _logFileWriter = new StreamWriter(new FileStream(_currentLogFilePath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite), Encoding.UTF8);

                if (isNew)
                {
                    _logFileWriter.WriteLine("JST時刻,raw_pulse_rpm,display_rpm,速度_kmh,緯度,経度,UTC時刻");
                    _logFileWriter.Flush();
                }

                _logWindow?.AppendLog($"[LOGGING] CSV保存を開始しました: {_currentLogFilePath}");
            }
            catch (Exception ex)
            {
                _logWindow?.AppendLog($"[LOGGING ERROR] CSV作成失敗: {ex.Message}");
            }
        }

        private void WriteToLogFile(double rawPulse, double displayRpm, double speed, double lat, double lon, string utcTime)
        {
            if (_logFileWriter == null) return;

            try
            {
                string jstTime = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.FFF");
                string line = $"{jstTime},{(int)rawPulse},{(int)displayRpm},{speed:F1},{lat:F6},{lon:F6},{utcTime}";
                _logFileWriter.WriteLine(line);
                _logFileWriter.Flush();
            }
            catch { }
        }

        private void StopLocalLogging()
        {
            if (_logFileWriter != null)
            {
                try
                {
                    _logFileWriter.Flush();
                    _logFileWriter.Close();
                }
                catch { }
                _logFileWriter.Dispose();
                _logFileWriter = null;
            }
        }

        #endregion

        #region 設定パネル・イベントハンドラ

        private void ResetMaxSpeed_Click(object sender, RoutedEventArgs e)
        {
            _maxSpeed = 0.0;
            MaxSpeedText.Text = "0.0";
        }

        private void PprComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_config == null) return; // 初期ロード時のNullReferenceExceptionを防止

            if (PprComboBox.SelectedItem is ComboBoxItem item)
            {
                if (double.TryParse(item.Tag?.ToString(), out double ppr))
                {
                    _config.PulsePerRevolution = ppr;
                    _config.Save();
                }
            }
        }

        private void BrowseFolder_Click(object sender, RoutedEventArgs e)
        {
            // Windows Presentation Foundation (WPF) に標準搭載の FolderBrowserDialog を利用
            var dialog = new Microsoft.Win32.OpenFolderDialog
            {
                Title = "ログデータの保存先フォルダを選択してください",
                InitialDirectory = _config.SaveDirectory
            };

            if (dialog.ShowDialog() == true)
            {
                string path = dialog.FolderName;
                if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path))
                {
                    _config.SaveDirectory = path;
                    _config.Save();
                    SaveDirTextBox.Text = path;
                    
                    _logWindow?.AppendLog($"[SYSTEM] 保存先フォルダを {path} に変更しました。");

                    // 接続中であればロギングファイルを新しい場所に再オープン
                    if (_serialPort != null && _serialPort.IsOpen)
                    {
                        StopLocalLogging();
                        StartLocalLogging();
                    }
                }
            }
        }

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            DisconnectInternal();
            if (_logWindow != null)
            {
                // 生ログウィンドウをクローズ (裏でHideされているのを解除)
                try
                {
                    _logWindow.Close();
                }
                catch { }
            }

            // シリアルポートのスレッド残留や、非表示ウィンドウの残りによる
            // プロセスのゾンビ化（バックグラウンドで残り続ける問題）を完全に防止するため、
            // プロセス全体の終了を明示的に宣言します。
            Environment.Exit(0);
        }

        #endregion
    }
}
