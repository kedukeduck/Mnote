package com.codex.mnote;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Local BYOK profiles. Secrets never enter Activity saved state or account sync. */
public final class AiModelSettingsActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout body;private String scope;private boolean destroyed,busy;
    @Override public void onCreate(Bundle state){super.onCreate(state);scope=CaptureAccountSession.scope(this);render();}
    private void render(){
        body=AiUi.body(this,AiUi.page(this,"AI 模型配置"));
        TextView intro=JournalUi.text(this,"让记录成为讨论的起点",26,R.color.ink);intro.setTypeface(android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL));body.addView(intro);
        body.addView(JournalUi.text(this,"模型服务由你选择。配置与密钥仅保存在这台设备；聊天历史可随 Mnote 账号同步。",14,R.color.ink_muted));
        try{
            JSONArray profiles=AiModelPreferences.list(this);
            JournalUi.section(body,"我的模型");
            if(profiles.length()==0)body.addView(JournalUi.text(this,"还没有模型。添加配置后，即可围绕单条记录聊天。",15,R.color.ink_muted));
            for(int i=0;i<profiles.length();i++){
                JSONObject p=profiles.getJSONObject(i);String id=p.getString("id");
                JournalUi.row(body,p.optString("label")+(p.optBoolean("is_default")?" · 默认":""),
                        p.optString("model")+"\n"+p.optString("base_url")+(p.optBoolean("vision")?" · 图片已启用":" · 仅文字"),()->edit(id));
            }
        }catch(Exception error){body.addView(JournalUi.text(this,AiUi.error(error.getMessage()),14,R.color.ink_muted));}
        AiUi.button(body,"添加模型",true,()->edit(null)).setId(R.id.ai_model_add);
        JournalUi.section(body,"连接与隐私");
        body.addView(JournalUi.text(this,"接口：OpenAI-compatible Chat Completions\n仅连接你填写的 HTTPS 地址，不跟随重定向。\n不会自动发送笔记；首次聊天会确认资料范围和服务商。\n测试会发送合成内容，可能产生少量费用。图片开关不代表服务商已验证支持。",14,R.color.ink_muted));
    }
    private void edit(String id){
        if(busy)return;
        LinearLayout panel=JournalUi.column(this);panel.setPadding(AiUi.dp(this,20),0,AiUi.dp(this,20),AiUi.dp(this,12));
        EditText label=AiUi.input(panel,"配置名称","例如：日常思考",false);label.setId(R.id.ai_model_label);
        EditText url=AiUi.input(panel,"API 服务地址","https://服务地址/v1",false);url.setId(R.id.ai_model_url);
        EditText model=AiUi.input(panel,"模型名称","服务商提供的模型 ID",false);model.setId(R.id.ai_model_name);
        EditText key=AiUi.input(panel,"API Key",id==null?"填写密钥":"留空保留已有密钥",true);key.setId(R.id.ai_model_key);
        CheckBox vision=new CheckBox(this);vision.setId(R.id.ai_model_vision);vision.setText("允许向此模型发送图片");vision.setMinHeight(AiUi.dp(this,48));panel.addView(vision);
        CheckBox makeDefault=new CheckBox(this);makeDefault.setText("设为新对话的默认模型");makeDefault.setMinHeight(AiUi.dp(this,48));panel.addView(makeDefault);
        if(id!=null)try{
            AiModelPreferences.Config p=AiModelPreferences.load(this,id);label.setText(p.label);url.setText(p.baseUrl);model.setText(p.model);vision.setChecked(p.vision);
            makeDefault.setChecked(id.equals(AiModelPreferences.defaultId(this)));
        }catch(Exception error){AiUi.showError(this,error);return;}else makeDefault.setChecked(true);
        TextView result=JournalUi.text(this,"密钥不会显示或同步。修改配置可能使旧会话需要重新选择模型。",13,R.color.ink_muted);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(result);
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(id==null?"添加模型":"编辑模型").setView(scroll)
                .setNegativeButton("取消",null).setPositiveButton("保存",null)
                .setNeutralButton(id==null?"保存并测试":"更多",null).create();
        dialog.setOnShowListener(d->{
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setId(R.id.ai_model_save);
            java.util.function.Consumer<Boolean> saveConfig=andTest->{
                if(busy)return;busy=true;save.setEnabled(false);
                String name=label.getText().toString(),base=url.getText().toString(),selectedModel=model.getText().toString(),secret=key.getText().toString();
                boolean images=vision.isChecked(),def=makeDefault.isChecked();
                io.execute(()->{try{
                    CaptureAccountSession.requireScope(this,scope);
                    String savedId=AiModelPreferences.save(this,id,name,base,selectedModel,secret,images,def);
                    runOnUiThread(()->{busy=false;if(destroyed||!scope.equals(CaptureAccountSession.scope(this)))return;key.setText("");dialog.dismiss();render();Toast.makeText(this,"模型配置已保存到本机",Toast.LENGTH_SHORT).show();
                        if(andTest){TextView feedback=JournalUi.text(this,"",14,R.color.ink_muted);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(feedback);test(savedId,false,feedback);}
                    });
                }catch(Exception error){runOnUiThread(()->{busy=false;if(destroyed)return;save.setEnabled(true);result.setText(AiUi.error(error.getMessage()));});}});
            };
            save.setOnClickListener(v->saveConfig.accept(false));
            Button test=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);test.setId(R.id.ai_model_test);
            test.setOnClickListener(v->{
                if(id==null){saveConfig.accept(true);return;}
                new AlertDialog.Builder(this).setTitle("模型操作").setItems(new String[]{"测试文字 / 流式（已保存配置）","测试图片（合成图片）","设为默认","删除配置"},(which,index)->{
                    if(index<2){test(id,index==1,result);}
                    else if(index==2){try{AiModelPreferences.setDefault(this,id);dialog.dismiss();render();}catch(Exception e){AiUi.showError(this,e);}}
                    else new AlertDialog.Builder(this).setTitle("删除这个模型配置？").setMessage("删除本机密钥，不删除聊天历史。历史仍可查看。")
                        .setNegativeButton("取消",null).setPositiveButton("删除",(a,b)->{try{AiModelPreferences.delete(this,id);dialog.dismiss();render();}catch(Exception e){AiUi.showError(this,e);}}).show();
                }).show();
            });
        });dialog.show();
    }
    private void test(String id,boolean image,TextView status){
        if(busy)return;
        new AlertDialog.Builder(this).setTitle(image?"测试图片能力？":"测试文字与流式连接？")
            .setMessage("只发送合成测试内容，不读取笔记。模型服务可能收取少量费用。")
            .setNegativeButton("取消",null).setPositiveButton("开始测试",(d,w)->{
                busy=true;status.setText("正在测试…");io.execute(()->{
                    String message;try{CaptureAccountSession.requireScope(this,scope);message=AiChatClient.test(AiModelPreferences.load(this,id),image);}
                    catch(Exception e){message=AiUi.error(e.getMessage());}
                    String output=message;runOnUiThread(()->{busy=false;if(!destroyed&&scope.equals(CaptureAccountSession.scope(this)))status.setText(output);});
                });
            }).show();
    }
    @Override protected void onResume(){super.onResume();if(scope!=null&&!scope.equals(CaptureAccountSession.scope(this)))finish();}
    @Override protected void onDestroy(){destroyed=true;io.shutdown();super.onDestroy();}
}
