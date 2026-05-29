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
            LogTextBox.Text = "--- 生ログデバッグモニターが起動しました ---" + Environment.NewLine;
        }

        /// <summary>
        /// スレッドセーフにログテキストを追記する。
        /// </summary>
        public void AppendLog(string text)
        {
            // ウィンドウが表示されていない（非表示）の場合は、一切描画処理を行わずリターン
            // これにより、非表示中の無駄なUIディスパッチ蓄積とメモリ肥大化を完全に防止します
            if (!this.IsVisible)
            {
                return;
            }

            if (!Dispatcher.CheckAccess())
            {
                Dispatcher.BeginInvoke(new Action(() => AppendLog(text)));
                return;
            }

            try
            {
                LogTextBox.AppendText(text + Environment.NewLine);
                
                // ログ量が大きくなりすぎた場合のメモリ保護（最大1000行）
                // 毎回巨大な文字列の切り捨て・コピーを行う処理はWPFの描画レイアウト計算を圧迫し、
                // 深刻なハングアップ（フリーズ）を引き起こすため、上限を超えたらクリアして高速化します
                if (LogTextBox.LineCount > 1000)
                {
                    LogTextBox.Clear();
                    LogTextBox.AppendText("--- ログが上限（1000行）に達したため自動クリアされました ---" + Environment.NewLine);
                }

                LogTextBox.ScrollToEnd();
            }
            catch
            {
                // 描画エラー等でアプリが落ちないように保護
            }
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
