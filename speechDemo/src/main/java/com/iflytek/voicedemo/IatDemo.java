package com.iflytek.voicedemo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.iflytek.cloud.ErrorCode;
import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.LexiconListener;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechSynthesizer;
import com.iflytek.cloud.SynthesizerListener;
import com.iflytek.cloud.ui.RecognizerDialog;
import com.iflytek.cloud.ui.RecognizerDialogListener;
import com.iflytek.cloud.util.ContactManager;
import com.iflytek.cloud.util.ContactManager.ContactListener;
import com.iflytek.speech.setting.IatSettings;
import com.iflytek.speech.setting.TtsSettings;
import com.iflytek.speech.util.FucUtil;
import com.iflytek.speech.util.JsonParser;
import com.iflytek.sunflower.FlowerCollector;



public class IatDemo<command> extends Activity implements OnClickListener {
	private static String TAG = IatDemo.class.getSimpleName();
	// 语音听写对象
	private SpeechRecognizer mIat;
	// 语音听写UI
	private RecognizerDialog mIatDialog;
	// 用HashMap存储听写结果
	private HashMap<String, String> mIatResults = new LinkedHashMap<String, String>();

	private EditText mResultText;
	private EditText showContacts;

	private boolean mTranslateEnable = false;
	private String resultType = "json";

	private boolean cyclic = false;//音频流识别是否循环调用

	private StringBuffer buffer = new StringBuffer();

	private SpeechSynthesizer mTts;
	private Toast mToastTTS;
	private SharedPreferences mSharedPreferencesTTS;

	// 默认发音人
	private String voicer = "xiaoyan";

	private String[] mCloudVoicersEntries;
	private String[] mCloudVoicersValue ;
	private String[] sentences;
	private String[] command = {"重复。", "再读一下。","read the last sentence。"};
	private int num_sentence = 20;
	private int now_sentence = -1;
	private int num_show =5;
	String texts = "";

	// 缓冲进度
	private int mPercentForBuffering = 0;
	// 播放进度
	private int mPercentForPlaying = 0;

	// 云端/本地单选按钮
	private RadioGroup mRadioGroup;
	// 引擎类型
	private String mEngineType = SpeechConstant.TYPE_CLOUD;

	private Toast mToast;
	private SharedPreferences mSharedPreferences;
	Handler handler;
	boolean isRecognizing;

	private void setParamtts(){
		// 清空参数
		mTts.setParameter(SpeechConstant.PARAMS, null);
		// 根据合成引擎设置相应参数
		if(mEngineType.equals(SpeechConstant.TYPE_CLOUD)) {
			mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
			//支持实时音频返回，仅在synthesizeToUri条件下支持
			//mTts.setParameter(SpeechConstant.TTS_DATA_NOTIFY, "1");
			// 设置在线合成发音人
			mTts.setParameter(SpeechConstant.VOICE_NAME, voicer);
			//设置合成语速
			mTts.setParameter(SpeechConstant.SPEED, mSharedPreferencesTTS.getString("speed_preference", "50"));
			//设置合成音调
			mTts.setParameter(SpeechConstant.PITCH, mSharedPreferencesTTS.getString("pitch_preference", "50"));
			//设置合成音量
			mTts.setParameter(SpeechConstant.VOLUME, mSharedPreferencesTTS.getString("volume_preference", "50"));
		}else {
			mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_LOCAL);
			mTts.setParameter(SpeechConstant.VOICE_NAME, "");

		}
		//设置播放器音频流类型
		mTts.setParameter(SpeechConstant.STREAM_TYPE, mSharedPreferencesTTS.getString("stream_preference", "3"));
		// 设置播放合成音频打断音乐播放，默认为true
		mTts.setParameter(SpeechConstant.KEY_REQUEST_FOCUS, "true");

		// 设置音频保存路径，保存音频格式支持pcm、wav，设置路径为sd卡请注意WRITE_EXTERNAL_STORAGE权限
		mTts.setParameter(SpeechConstant.AUDIO_FORMAT, "pcm");
		mTts.setParameter(SpeechConstant.TTS_AUDIO_PATH, Environment.getExternalStorageDirectory()+"/msc/tts.pcm");
	}

	Handler han = new Handler(){

		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			if (msg.what == 0x001) {
				executeStream();
			}
		}
	};


	@SuppressLint("ShowToast")
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.iatdemo);

		initLayout();
		// 初始化识别无UI识别对象
		// 使用SpeechRecognizer对象，可根据回调消息自定义界面；
		mIat = SpeechRecognizer.createRecognizer(IatDemo.this, mInitListener);
		mTts = SpeechSynthesizer.createSynthesizer(IatDemo.this, mInitListener);
		sentences = new String[num_sentence];
		// 初始化听写Dialog，如果只使用有UI听写功能，无需创建SpeechRecognizer
		// 使用UI听写功能，请根据sdk文件目录下的notice.txt,放置布局文件和图片资源
		mIatDialog = new RecognizerDialog(IatDemo.this, mInitListener);

		mSharedPreferences = getSharedPreferences(IatSettings.PREFER_NAME,
				Activity.MODE_PRIVATE);
		mSharedPreferencesTTS = getSharedPreferences(TtsSettings.PREFER_NAME, MODE_PRIVATE);
		mToast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
		mResultText = ((EditText) findViewById(R.id.iat_text));
		showContacts = (EditText) findViewById(R.id.iat_contacts);
		/*handler = new Handler(){
		    @Override
            public void handleMessage(Message msg)
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                    findViewById(R.id.iat_recognize).callOnClick();
                }
            }
        };
		Mythread thread = new Mythread();
		thread.start();*/
		//new HTTPCheckingEye().execute();
	}




	/**
	 * 初始化Layout。
	 */
	private void initLayout() {
		findViewById(R.id.iat_recognize).setOnClickListener(IatDemo.this);
		findViewById(R.id.iat_recognize_stream).setOnClickListener(IatDemo.this);
//		findViewById(R.id.iat_upload_contacts).setOnClickListener(IatDemo.this);
//		findViewById(R.id.iat_upload_userwords).setOnClickListener(IatDemo.this);
		findViewById(R.id.iat_stop).setOnClickListener(IatDemo.this);
		findViewById(R.id.iat_cancel).setOnClickListener(IatDemo.this);
		findViewById(R.id.image_iat_set).setOnClickListener(IatDemo.this);
	}

	int ret = 0; // 函数调用返回值

	@Override
	public void onClick(View view) {
		if( null == mIat ){
			// 创建单例失败，与 21001 错误为同样原因，参考 http://bbs.xfyun.cn/forum.php?mod=viewthread&tid=9688
			this.showTip( "创建对象失败，请确认 libmsc.so 放置正确，且有调用 createUtility 进行初始化" );
			return;
		}
		
		switch (view.getId()) {
		// 进入参数设置页面
		case R.id.image_iat_set:
			Intent intents = new Intent(IatDemo.this, IatSettings.class);
			startActivity(intents);
			break;
		// 开始听写
		// 如何判断一次听写结束：OnResult isLast=true 或者 onError
		case R.id.iat_recognize:
			// 移动数据分析，收集开始听写事件
			FlowerCollector.onEvent(IatDemo.this, "iat_recognize");

			buffer.setLength(0);
			mResultText.setText(null);// 清空显示内容
			mIatResults.clear();
			// 设置参数
			setParam();
			boolean isShowDialog = mSharedPreferences.getBoolean(
					getString(R.string.pref_key_iat_show), true);
			if (isShowDialog) {
				// 显示听写对话框
				mIatDialog.setListener(mRecognizerDialogListener);
				mIatDialog.show();
				showTip(getString(R.string.text_begin));
				isRecognizing = true;
			} else {
				// 不显示听写对话框
				ret = mIat.startListening(mRecognizerListener);
				if (ret != ErrorCode.SUCCESS) {
					showTip("听写失败,错误码：" + ret+",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
				} else {
					showTip(getString(R.string.text_begin));
				}
			}
			break;
		// 音频流识别
		case R.id.iat_recognize_stream:
			executeStream();
			break;
		// 停止听写
		case R.id.iat_stop:
			mIat.stopListening();
			showTip("停止听写");
			break;
		// 取消听写
		case R.id.iat_cancel:
			mIat.cancel();
			showTip("取消听写");
			break;
		// 上传联系人
//		case R.id.iat_upload_contacts:
//			showTip(getString(R.string.text_upload_contacts));
//			ContactManager mgr = ContactManager.createManager(IatDemo.this,
//					mContactListener);
//			mgr.asyncQueryAllContactsName();
//			break;
//		// 上传用户词表
//		case R.id.iat_upload_userwords:
//			showTip(getString(R.string.text_upload_userwords));
//			String contents = FucUtil.readFile(IatDemo.this, "userwords","utf-8");
//			showContacts.setText(contents);
//
//			// 指定引擎类型
//			mIat.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
//			mIat.setParameter(SpeechConstant.TEXT_ENCODING, "utf-8");
//			ret = mIat.updateLexicon("userword", contents, mLexiconListener);
//			if (ret != ErrorCode.SUCCESS)
//				showTip("上传热词失败,错误码：" + ret+",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
//			break;
		default:
			break;
		}
	}

	/**
	 * 初始化监听器。
	 */
	private InitListener mInitListener = new InitListener() {

		@Override
		public void onInit(int code) {
			Log.d(TAG, "SpeechRecognizer init() code = " + code);
			if (code != ErrorCode.SUCCESS) {
				showTip("初始化失败，错误码：" + code+",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
			}
		}
	};

	/**
	 * 上传联系人/词表监听器。
	 */
	private LexiconListener mLexiconListener = new LexiconListener() {

		@Override
		public void onLexiconUpdated(String lexiconId, SpeechError error) {
			if (error != null) {
				showTip(error.toString());
			} else {
				showTip(getString(R.string.text_upload_success));
			}
		}
	};

	/**
	 * 听写监听器。
	 */
	private RecognizerListener mRecognizerListener = new RecognizerListener() {

		@Override
		public void onBeginOfSpeech() {
			// 此回调表示：sdk内部录音机已经准备好了，用户可以开始语音输入
			showTip("开始说话");
			//isRecognizing = true;
		}

		@Override
		public void onError(SpeechError error) {
			// Tips：
			// 错误码：10118(您没有说话)，可能是录音机权限被禁，需要提示用户打开应用的录音权限。
			if(mTranslateEnable && error.getErrorCode() == 14002) {
				showTip( error.getPlainDescription(true)+"\n请确认是否已开通翻译功能" );
			} else {
				showTip(error.getPlainDescription(true));
			}
		}

		@Override
		public void onEndOfSpeech() {
			// 此回调表示：检测到了语音的尾端点，已经进入识别过程，不再接受语音输入
			showTip("结束说话");
			//isRecognizing = false;
		}

		@Override
		public void onResult(RecognizerResult results, boolean isLast) {
			Log.d(TAG, results.getResultString());
			if (resultType.equals("json")) {
				if( mTranslateEnable ){
					printTransResult( results );
				}else{
					printResult(results);
				}
			}else if(resultType.equals("plain")) {
				buffer.append(results.getResultString());
				mResultText.setText(buffer.toString());
				mResultText.setSelection(mResultText.length());
			}

			if (isLast & cyclic) {
				// TODO 最后的结果
				Message message = Message.obtain();
				message.what = 0x001;
				han.sendMessageDelayed(message,100);
			}
		}

		@Override
		public void onVolumeChanged(int volume, byte[] data) {
			showTip("当前正在说话，音量大小：" + volume);
			Log.d(TAG, "返回音频数据："+data.length);
		}

		@Override
		public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
			// 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
			// 若使用本地能力，会话id为null
			//	if (SpeechEvent.EVENT_SESSION_ID == eventType) {
			//		String sid = obj.getString(SpeechEvent.KEY_EVENT_SESSION_ID);
			//		Log.d(TAG, "session id =" + sid);
			//	}
		}
	};

	private void printResult(RecognizerResult results) {
		String text = JsonParser.parseIatResult(results.getResultString());
        Log.i("listen",text);
		String sn = null;
		// 读取json结果中的sn字段
		try {
			JSONObject resultJson = new JSONObject(results.getResultString());
			sn = resultJson.optString("sn");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		mIatResults.put(sn, text);

		StringBuffer resultBuffer = new StringBuffer();
		for (String key : mIatResults.keySet()) {
			resultBuffer.append(mIatResults.get(key));
		}
        Log.i("listen",resultBuffer.toString());
		String result_now = resultBuffer.toString();
		String last = result_now.substring(result_now.length()-1);
		Log.i("listen",last);
		boolean complete = (last.equals("。") || last.equals("？") || last.equals("！"));
		if (complete != true)
			return;

		if(result_now.equals(command[0])) {
			Log.i("listen", "complete");
			//new HTTPRequestTask().execute(resultBuffer.toString());
			setParamtts();
			if (now_sentence > -1) {
				int code = mTts.startSpeaking(sentences[now_sentence], mTtsListener);

			}
			else
			{
				int code = mTts.startSpeaking(new String("No last sentence."), mTtsListener);
			}
			result_now = "command[0] - " + result_now;
		}
		now_sentence += 1;
		sentences[now_sentence] = result_now;

		String toshow = "";
		int start = now_sentence - num_show;
		if (start < 0)
			start = 0;
		for(int i =start;i <= now_sentence;i++)
		{
			toshow += sentences[i] + "\n";
		}
		mResultText.setText(toshow);
		mResultText.setSelection(mResultText.length());

	}

	/**
	 * 听写UI监听器
	 */
	private RecognizerDialogListener mRecognizerDialogListener = new RecognizerDialogListener() {
		public void onResult(RecognizerResult results, boolean isLast) {
			if( mTranslateEnable ){
				printTransResult( results );
			}else{
				printResult(results);
			}
			
		}

		/**
		 * 识别回调错误.
		 */
		public void onError(SpeechError error) {
			if(mTranslateEnable && error.getErrorCode() == 14002) {
				showTip( error.getPlainDescription(true)+"\n请确认是否已开通翻译功能" );
			} else {
				showTip(error.getPlainDescription(true));
			}
		}

	};

	/**
	 * 获取联系人监听器。
	 */
	private ContactListener mContactListener = new ContactListener() {

		@Override
		public void onContactQueryFinish(final String contactInfos, boolean changeFlag) {
			// 注：实际应用中除第一次上传之外，之后应该通过changeFlag判断是否需要上传，否则会造成不必要的流量.
			// 每当联系人发生变化，该接口都将会被回调，可通过ContactManager.destroy()销毁对象，解除回调。
			// if(changeFlag) {
			// 指定引擎类型
			runOnUiThread(new Runnable() {
				public void run() {
					showContacts.setText(contactInfos);
				}
			});
			
			mIat.setParameter(SpeechConstant.ENGINE_TYPE,SpeechConstant.TYPE_CLOUD);
			mIat.setParameter(SpeechConstant.TEXT_ENCODING, "utf-8");
			ret = mIat.updateLexicon("contact", contactInfos, mLexiconListener);
			if (ret != ErrorCode.SUCCESS) {
				showTip("上传联系人失败：" + ret);
			}
		}
	};

	private void showTip(final String str) {
		mToast.setText(str);
		mToast.show();
	}

	/**
	 * 参数设置
	 * 
	 * @return
	 */
	public void setParam() {
		// 清空参数
		mIat.setParameter(SpeechConstant.PARAMS, null);

		// 设置听写引擎
		mIat.setParameter(SpeechConstant.ENGINE_TYPE, mEngineType);
		// 设置返回结果格式
		mIat.setParameter(SpeechConstant.RESULT_TYPE, resultType);
		
		this.mTranslateEnable = mSharedPreferences.getBoolean( this.getString(R.string.pref_key_translate), false );
		if( mTranslateEnable ){
			Log.i( TAG, "translate enable" );
			mIat.setParameter( SpeechConstant.ASR_SCH, "1" );
			mIat.setParameter( SpeechConstant.ADD_CAP, "translate" );
			mIat.setParameter( SpeechConstant.TRS_SRC, "its" );
		}

		String lag = mSharedPreferences.getString("iat_language_preference",
				"mandarin");
		if (lag.equals("en_us")) {
			// 设置语言
			mIat.setParameter(SpeechConstant.LANGUAGE, "en_us");
			mIat.setParameter(SpeechConstant.ACCENT, null);
			
			if( mTranslateEnable ){
				mIat.setParameter( SpeechConstant.ORI_LANG, "en" );
				mIat.setParameter( SpeechConstant.TRANS_LANG, "cn" );
			}
		} else {
			// 设置语言
			mIat.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
			// 设置语言区域
			mIat.setParameter(SpeechConstant.ACCENT, lag);
			
			if( mTranslateEnable ){
				mIat.setParameter( SpeechConstant.ORI_LANG, "cn" );
				mIat.setParameter( SpeechConstant.TRANS_LANG, "en" );
			}
		}
		//此处用于设置dialog中不显示错误码信息
		//mIat.setParameter("view_tips_plain","false");

		// 设置语音前端点:静音超时时间，即用户多长时间不说话则当做超时处理
		mIat.setParameter(SpeechConstant.VAD_BOS, mSharedPreferences.getString("iat_vadbos_preference", "4000"));
		
		// 设置语音后端点:后端点静音检测时间，即用户停止说话多长时间内即认为不再输入， 自动停止录音
		mIat.setParameter(SpeechConstant.VAD_EOS, mSharedPreferences.getString("iat_vadeos_preference", "1000"));
		
		// 设置标点符号,设置为"0"返回结果无标点,设置为"1"返回结果有标点
		mIat.setParameter(SpeechConstant.ASR_PTT, mSharedPreferences.getString("iat_punc_preference", "1"));
		
		// 设置音频保存路径，保存音频格式支持pcm、wav，设置路径为sd卡请注意WRITE_EXTERNAL_STORAGE权限
		mIat.setParameter(SpeechConstant.AUDIO_FORMAT,"wav");
		mIat.setParameter(SpeechConstant.ASR_AUDIO_PATH, Environment.getExternalStorageDirectory()+"/msc/iat.wav");
	}
	
	private void printTransResult (RecognizerResult results) {
		String trans  = JsonParser.parseTransResult(results.getResultString(),"dst");
		String oris = JsonParser.parseTransResult(results.getResultString(),"src");

		if( TextUtils.isEmpty(trans)||TextUtils.isEmpty(oris) ){
			showTip( "解析结果失败，请确认是否已开通翻译功能。" );
		}else{
			mResultText.setText( "原始语言:\n"+oris+"\n目标语言:\n"+trans );
		}

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		
		if( null != mIat ){
			// 退出时释放连接
			mIat.cancel();
			mIat.destroy();
		}
	}

	@Override
	protected void onResume() {
		// 开放统计 移动数据统计分析
		FlowerCollector.onResume(IatDemo.this);
		FlowerCollector.onPageStart(TAG);
		super.onResume();
	}

	@Override
	protected void onPause() {
		// 开放统计 移动数据统计分析
		FlowerCollector.onPageEnd(TAG);
		FlowerCollector.onPause(IatDemo.this);
		super.onPause();
	}

	//执行音频流识别操作
	private void executeStream() {
		buffer.setLength(0);
		mResultText.setText(null);// 清空显示内容
		mIatResults.clear();
		// 设置参数
		setParam();
		// 设置音频来源为外部文件
		mIat.setParameter(SpeechConstant.AUDIO_SOURCE, "-1");
		// 也可以像以下这样直接设置音频文件路径识别（要求设置文件在sdcard上的全路径）：
		// mIat.setParameter(SpeechConstant.AUDIO_SOURCE, "-2");
		 //mIat.setParameter(SpeechConstant.ASR_SOURCE_PATH, "sdcard/XXX/XXX.pcm");
		ret = mIat.startListening(mRecognizerListener);
		if (ret != ErrorCode.SUCCESS) {
			showTip("识别失败,错误码：" + ret+",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
		} else {
			byte[] audioData = FucUtil.readAudioFile(IatDemo.this, "iattest.wav");

			if (null != audioData) {
				showTip(getString(R.string.text_begin_recognizer));
				// 一次（也可以分多次）写入音频文件数据，数据格式必须是采样率为8KHz或16KHz（本地识别只支持16K采样率，云端都支持），
				// 位长16bit，单声道的wav或者pcm
				// 写入8KHz采样的音频时，必须先调用setParameter(SpeechConstant.SAMPLE_RATE, "8000")设置正确的采样率
				// 注：当音频过长，静音部分时长超过VAD_EOS将导致静音后面部分不能识别。
				// 音频切分方法：FucUtil.splitBuffer(byte[] buffer,int length,int spsize);
				mIat.writeAudio(audioData, 0, audioData.length);

				mIat.stopListening();
			} else {
				mIat.cancel();
				showTip("读取音频流失败");
			}
		}
	}
	private SynthesizerListener mTtsListener = new SynthesizerListener() {

		@Override
		public void onSpeakBegin() {
			showTip("开始播放");
		}

		@Override
		public void onSpeakPaused() {
			showTip("暂停播放");
		}

		@Override
		public void onSpeakResumed() {
			showTip("继续播放");
		}

		@Override
		public void onBufferProgress(int percent, int beginPos, int endPos,
									 String info) {
			// 合成进度
			mPercentForBuffering = percent;
			showTip(String.format(getString(R.string.tts_toast_format),
					mPercentForBuffering, mPercentForPlaying));
		}
		@Override
		public void onSpeakProgress(int percent, int beginPos, int endPos) {
			// 播放进度
			mPercentForPlaying = percent;
			showTip(String.format(getString(R.string.tts_toast_format),
					mPercentForBuffering, mPercentForPlaying));

			SpannableStringBuilder style=new SpannableStringBuilder(texts);
			Log.e(TAG,"beginPos = "+beginPos +"  endPos = "+endPos);
			style.setSpan(new BackgroundColorSpan(Color.RED),beginPos,endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			((EditText) findViewById(R.id.tts_text)).setText(style);
		}

		@Override
		public void onCompleted(SpeechError error) {
			if (error == null) {
				showTip("播放完成");
			} else if (error != null) {
				showTip(error.getPlainDescription(true));
			}
		}
		@Override
		public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
			// 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
			// 若使用本地能力，会话id为null
			//	if (SpeechEvent.EVENT_SESSION_ID == eventType) {
			//		String sid = obj.getString(SpeechEvent.KEY_EVENT_SESSION_ID);
			//		Log.d(TAG, "session id =" + sid);
			//	}

			//当设置SpeechConstant.TTS_DATA_NOTIFY为1时，抛出buf数据
			/*if (SpeechEvent.EVENT_TTS_BUFFER == eventType) {
						byte[] buf = obj.getByteArray(SpeechEvent.KEY_EVENT_TTS_BUFFER);
						Log.e("MscSpeechLog", "buf is =" + buf);
					}*/

		}
	};

	class Mythread extends Thread{
        double threshold_check = 0.9;
        int length_check = 5;
        int amount_check = 0;
        ArrayDeque<Integer> result_check = new ArrayDeque<Integer>();
	    @Override
        public void run ()
        {
            String url_check = "http://183.173.115.37:8080";

            try {
                while(true) {
                    if(mTts.isSpeaking() == true || isRecognizing == true) {
                        Thread.sleep(50);
                        continue;
                    }
                    URL url = new URL(url_check);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setDoInput(true);
                    conn.setUseCaches(false);
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    byte[] responseBody = null;

                    BufferedInputStream bis = new BufferedInputStream(is);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BufferedOutputStream bos = new BufferedOutputStream(baos);
                    byte[] buffer = new byte[1024 * 8];
                    int length = 0;
                    try {
                        while ((length = bis.read(buffer)) > 0) {
                            bos.write(buffer, 0, length);
                            bos.flush();
                            responseBody = baos.toByteArray();
                            Log.i("result", new String(responseBody));
                            int result = Integer.parseInt(new String(responseBody));
                            if (result_check.size() < length_check) {
                                result_check.add(result);
                            } else {
                                result_check.poll();
                                result_check.add(result);
                            }
                            if (mTts.isSpeaking() == false && isRecognizing == false && result_check.size() == length_check) {
                                amount_check = 0;
                                Iterator<Integer> it = result_check.iterator();
                                while (it.hasNext()) {
                                    amount_check += it.next();
                                }
                                if (amount_check > length_check * threshold_check) {
                                    Log.i("listen","start listening");
                                    result_check.clear();
                                    handler.sendEmptyMessage(0);
                                }
                            }
                        }
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                    Thread.sleep(50);
                }
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
	class HTTPCheckingEye extends AsyncTask<Void, Void, Void> {
        double threshold_check = 0.9;
        int length_check = 5;
        int amount_check = 0;
        ArrayDeque<Integer> result_check = new ArrayDeque<Integer>();

        @Override
        protected Void doInBackground(Void... voids) {
            String url_check = "http://183.173.115.37:8080";

            try {
                while(true) {
                    URL url = new URL(url_check);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setDoInput(true);
                    conn.setUseCaches(false);
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    byte[] responseBody = null;

                    BufferedInputStream bis = new BufferedInputStream(is);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BufferedOutputStream bos = new BufferedOutputStream(baos);
                    byte[] buffer = new byte[1024 * 8];
                    int length = 0;
                    try {
                        while ((length = bis.read(buffer)) > 0) {
                            bos.write(buffer, 0, length);
                            bos.flush();
                            responseBody = baos.toByteArray();
                            Log.i("result", new String(responseBody));
                            int result = Integer.parseInt(new String(responseBody));
                            if (result_check.size() < length_check) {
                                result_check.add(result);
                            } else {
                                result_check.poll();
                                result_check.add(result);
                            }
                            if (mTts.isSpeaking() == false && isRecognizing == false && result_check.size() == length_check) {
                                amount_check = 0;
                                Iterator<Integer> it = result_check.iterator();
                                while (it.hasNext()) {
                                    amount_check += it.next();
                                }
                                if (amount_check > length_check * threshold_check) {
                                    Log.i("listen","start listening");
                                    //handler.sendEmptyMessage(0);
                                }
                            }
                        }
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                    Thread.sleep(50);
                }
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

	class HTTPRequestTask extends AsyncTask<String,Void, String> {

		@Override
		protected String doInBackground(String... strings) {
			String url_text = "http://183.173.115.37:8000/test?";
			url_text += strings[0].toString();
			Log.i("web", url_text);
			try {
				URL url = new URL(url_text);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setRequestMethod("GET");
				conn.setDoInput(true);
				conn.setUseCaches(false);
				conn.connect();
				InputStream is = conn.getInputStream();
				byte[] responseBody = null;
				BufferedInputStream bis = new BufferedInputStream(is);
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				BufferedOutputStream bos = new BufferedOutputStream(baos);
				byte[] buffer = new byte[1024 * 8];
				int length = 0;
				try {
					while ((length = bis.read(buffer)) > 0) {
						bos.write(buffer, 0, length);
						bos.flush();
						responseBody = baos.toByteArray();
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					try {
						bos.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
					try {
						bis.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				mResultText.setText( new String(responseBody));//resultBuffer.toString());
				mResultText.setSelection(mResultText.length());
				setParamtts();
				int code = mTts.startSpeaking( new String(responseBody),mTtsListener);
                isRecognizing = false;
				/*
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
					findViewById(R.id.iat_recognize).callOnClick();
				}*/
				return new String(responseBody);
			} catch (ProtocolException e) {
				e.printStackTrace();
			} catch (MalformedURLException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}

			return null;
		}
	}
}
