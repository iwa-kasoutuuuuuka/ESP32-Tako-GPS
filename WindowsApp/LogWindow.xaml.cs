using System;
using System.ComponentModel;
using System.Windows;

namespace ESP32LoggerWin
{
    public partial class LogWindow : Window
    {
        public LogWindow()
        {
            InitializeComponent();
        }

        /// <summary>
        /// スレッドセーフにログテキストを追記する。
        /// </summary>
        public void AppendLog(string text)
        {
            if (!Dispatcher.CheckAccess())
            {
                Dispatcher.Invoke(() => AppendLog(text));
                return;
            }

            LogTextBox.AppendText(text + Environment.NewLine);
            
            // ログ量が大きくなりすぎた場合のメモリ保護（最大5000行）
            if (LogTextBox.LineCount > 5000)
            {
                LogTextBox.Text = LogTextBox.Text.Substring(LogTextBox.Text.Length / 2);
            }

            LogTextBox.ScrollToEnd();
        }

        private void ClearButton_Click(object sender, RoutedEventArgs e)
        {
            LogTextBox.Clear();
        }

        private void CloseButton_Click(object sender, RoutedEventArgs e)
        {
            this.Hide();
        }

        /// <summary>
        /// ウィンドウが閉じられるとき、インスタンスを破棄せず非表示 (Hide) にすることで
        /// メインウィンドウとの生存期間を合わせ、インスタンス再作成コストを省く
        /// </summary>
        protected override void OnClosing(CancelEventArgs e)
        {
            e.Cancel = true;
            this.Hide();
        }
    }
}
