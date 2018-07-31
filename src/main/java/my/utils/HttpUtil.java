package my.utils;

import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.widget.ProgressBar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A http util, packaged thread pool and use handler mutual with main thread, this can
 * get small data and large data, small data will direct put it to memory and return,
 * large data will need the caller provide a file name to store the data.
 *
 * @author djh on  2018/7/30 18:14
 * @E-Mail 1544579459@qq.com
 */
public class HttpUtil {
    private static boolean sStopGet = false;
    private static boolean sCancelGet = false;
    private static ExecutorService sExecutorService;
    private static Map<String, HttpURLConnection> sConnectionMap;

    static {
        sConnectionMap = new HashMap<>();
        sExecutorService = Executors.newCachedThreadPool();
    }

    /**
     * Get request, use handler send the response result to main thread.
     */
    public static void get(final String url, final Handler handler) {
        getSmallData(url, handler);
    }

    /**
     * Get large data from internet, and use handler show progress.
     */
    public static void get(String url, Handler handler, String fileName, ProgressBar progressBar) {
        getLargeData(url, handler, fileName, progressBar);
    }

    /**
     * Overload method, and not show progress.
     */
    public static void get(String url, Handler handler, String fileName) {
        getLargeData(url, handler, fileName, null);
    }

    private static void getSmallData(final String url, final Handler handler) {
        sExecutorService.execute(new Runnable() {
            private HttpURLConnection httpURLConnection = getConnect(url, handler, -1);

            @Override
            public void run() {
                try {
                    if (httpURLConnection == null) {
                        return;
                    }
                    // If request success, use handler send content to main thread.
                    byte[] bytes = read(httpURLConnection.getInputStream());
                    Message message = handler.obtainMessage();
                    message.obj = bytes;
                    handler.sendMessage(message);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                }
            }
        });
    }

    private static void getLargeData(final String url, final Handler handler, final String fileName, final ProgressBar progressBar) {
        sExecutorService.execute(new Runnable() {
            @Override
            public void run() {
                long alreadyReadSize;
                File file = new File(MyApplication.getContext().getFilesDir(), fileName);
                HttpURLConnection httpURLConnection;

                // Judge the file weather already exists.
                if (!file.exists() || file.length() == 0) {
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    httpURLConnection = getConnect(url, handler, -1);
                } else {
                    alreadyReadSize = file.length();
                    httpURLConnection = getConnect(url, handler, alreadyReadSize);
                }
                if (httpURLConnection == null) {
                    return;
                }
                // Put this connection to the map, when user stop get the data, get
                // the connection and disconnect.
                sConnectionMap.put(url, httpURLConnection);
                // Start download.
                boolean success = readLargeDataAndSaveToFile(file, httpURLConnection, handler, progressBar);
                if (success) {
                    showToast(handler, "下载成功");
                    sConnectionMap.remove(url);
                } else {
                    if (sStopGet) {
                        showToast(handler, "暂停下载");
                        sStopGet = false;
                    } else if (sCancelGet) {
                        showToast(handler, "取消下载");
                        sCancelGet = false;
                    } else {
                        showToast(handler, "下载失败");
                    }
                }
            }
        });
    }

    /**
     * This should use where you first use the get method, when the activity or fragment
     * destroy, you should use this to shutdown the inner cache thread pool;
     */
    public static void shutDownNow() {
        sExecutorService.shutdownNow();
    }

    /**
     * When the user stop get the data, use this disconnect.
     */
    public static void stopGet(String url) {
        HttpURLConnection httpURLConnection = sConnectionMap.get(url);
        if (httpURLConnection != null) {
            sConnectionMap.remove(url);
            httpURLConnection.disconnect();
            sStopGet = true;
        }
    }

    /**
     * Cancel get, if file name is not null, will delete the file.
     */
    public static void cancelGet(String url, @Nullable String fileName) {
        HttpURLConnection httpURLConnection = sConnectionMap.get(url);
        if (httpURLConnection != null) {
            sConnectionMap.remove(url);
            httpURLConnection.disconnect();
            sCancelGet = true;
        }
        if (fileName != null) {
            File file = new File(MyApplication.getContext().getFilesDir(), fileName);
            if (file.exists()) {
                file.delete();
            }
        }
        if (!sCancelGet) {
            ToastUtil.showToast("取消下载");
        }
    }

    /**
     * Get the http connection.
     */
    private static HttpURLConnection getConnect(String url, Handler handler, long alreadyReadSize) {
        try {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(url).openConnection();
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setReadTimeout(1000 * 10);
            httpURLConnection.setConnectTimeout(1000 * 10);
            if (alreadyReadSize != -1) {
                // This request heard means read from the last breakpoint, if request
                // success, the response code is 206, not is 200.
                httpURLConnection.setRequestProperty("range", "bytes=" + alreadyReadSize + "-");
            }
            httpURLConnection.connect();
            final int responseCode = httpURLConnection.getResponseCode();
            // If request fail, show toast and return.
            if (responseCode != 200 && responseCode != 206) {
                showToast(handler, "请求失败" + responseCode);
                return null;
            }
            return httpURLConnection;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Read the small data, put it to memory.
     */
    private static byte[] read(InputStream inputStream) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
        int hasRead;
        byte[] bytes = new byte[1024];
        try {
            while ((hasRead = bufferedInputStream.read(bytes)) != -1) {
                byteArrayOutputStream.write(bytes, 0, hasRead);
            }
            byteArrayOutputStream.flush();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                bufferedInputStream.close();
                byteArrayOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Get the large data, and save it to file, default location at files dir, and the
     * implement the breakpoint read.
     */
    private static boolean readLargeDataAndSaveToFile(File file, HttpURLConnection httpURLConnection, Handler handler, final ProgressBar progressBar) {
        long alreadyReadSize = file.length();
        // If you seek the breakpoint, the http return content length is the
        // remaining part, so the total size need add the already read size.
        final long totalSize = httpURLConnection.getContentLength() + alreadyReadSize;

        BufferedInputStream bufferedInputStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        try {
            bufferedInputStream = new BufferedInputStream(httpURLConnection.getInputStream());
            bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file, true));
            int hasRead;
            byte[] bytes = new byte[1024];
            while ((hasRead = bufferedInputStream.read(bytes)) != -1) {

                if (progressBar != null) {
                    // Show read progress.
                    alreadyReadSize += hasRead;
                    final long finalAlreadyReadSize = alreadyReadSize;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            int progressValue = (int) ((finalAlreadyReadSize / (float) totalSize) * 100);
                            progressBar.setProgress(progressValue);
                        }
                    });
                }

                // Save to file.
                bufferedOutputStream.write(bytes, 0, hasRead);
            }
            bufferedOutputStream.flush();
            // If success, return true.
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (bufferedOutputStream != null) {
                try {
                    bufferedOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bufferedInputStream != null) {
                try {
                    bufferedInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    private static void showToast(Handler handler, final String content) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                ToastUtil.showToast(content);
            }
        });
    }
}