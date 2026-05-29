using System;
using System.IO;
using System.Text.Json;

namespace ESP32LoggerWin
{
    public class AppConfig
    {
        public string SaveDirectory { get; set; } = string.Empty;
        public double PulsePerRevolution { get; set; } = 0.5;
        public string LastComPort { get; set; } = string.Empty;

        private static readonly string ConfigFileName = "config.json";
        private static readonly string DefaultPath = AppDomain.CurrentDomain.BaseDirectory;

        /// <summary>
        /// 設定を取得する。存在しない場合は新規デフォルト値を作成して保存する。
        /// </summary>
        public static AppConfig Load()
        {
            string path = Path.Combine(DefaultPath, ConfigFileName);
            try
            {
                if (File.Exists(path))
                {
                    string json = File.ReadAllText(path);
                    var config = JsonSerializer.Deserialize<AppConfig>(json);
                    if (config != null)
                    {
                        // 保存ディレクトリが空の場合はデフォルト（BaseDirectory）を設定
                        if (string.IsNullOrWhiteSpace(config.SaveDirectory))
                        {
                            config.SaveDirectory = DefaultPath;
                        }
                        return config;
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"設定のロードに失敗しました: {ex.Message}");
            }

            // フォールバック用のデフォルト設定
            var defaultConfig = new AppConfig
            {
                SaveDirectory = DefaultPath,
                PulsePerRevolution = 0.5,
                LastComPort = string.Empty
            };
            defaultConfig.Save();
            return defaultConfig;
        }

        /// <summary>
        /// 設定を config.json に書き出し保存する。
        /// </summary>
        public void Save()
        {
            string path = Path.Combine(DefaultPath, ConfigFileName);
            try
            {
                var options = new JsonSerializerOptions { WriteIndented = true };
                string json = JsonSerializer.Serialize(this, options);
                File.WriteAllText(path, json);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"設定の保存に失敗しました: {ex.Message}");
            }
        }
    }
}
