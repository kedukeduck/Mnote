package com.codex.mnote;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;

/** A deliberately inert Markdown subset: no HTML, remote images, scripts or automatic links. */
final class AiMessageText {
    static CharSequence render(String markdown) {
        return render(markdown,0xff955530);
    }
    static CharSequence render(String markdown,int quoteColor) {
        SpannableStringBuilder out=new SpannableStringBuilder();boolean code=false;
        for(String line:markdown.split("\n",-1)) {
            if(line.trim().startsWith("```")){code=!code;continue;}
            int start=out.length();String text=line;boolean heading=!code&&text.matches("^#{1,6} .*");
            boolean quote=!code&&text.startsWith("> ");
            if(heading)text=text.replaceFirst("^#{1,6} +","");
            if(quote)text=text.substring(2);
            if(!code&&text.matches("^[-*] .+"))text="• "+text.substring(2);
            if(code)out.append(text);else inline(out,text);
            int end=out.length();
            if(end>start){
                if(code)out.setSpan(new TypefaceSpan("monospace"),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if(heading){out.setSpan(new StyleSpan(Typeface.BOLD),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);out.setSpan(new RelativeSizeSpan(1.12f),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
                if(quote)out.setSpan(new QuoteSpan(quoteColor),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append('\n');
        }
        if(out.length()>0)out.delete(out.length()-1,out.length());return out;
    }
    private static void inline(SpannableStringBuilder out,String text) {
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`").matcher(text);int previous=0;
        while(match.find()){
            out.append(text,previous,match.start());int start=out.length();
            out.append(match.group(1)!=null?match.group(1):match.group(2));
            out.setSpan(match.group(1)!=null?new StyleSpan(Typeface.BOLD):new TypefaceSpan("monospace"),start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            previous=match.end();
        }
        out.append(text,previous,text.length());
    }
}
