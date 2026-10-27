package app.agentichydra

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.node.Code
import org.commonmark.node.Heading
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal data class MarkdownMetrics(val typeface:Typeface,val size:Float,val lineHeight:Int)

internal class MarkdownRenderer(val markwon:Markwon,val metrics:MarkdownMetrics,val foreground:Int,val link:Int,val paragraphGap:Int) {
    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=metrics.typeface;textSize=metrics.size}
    fun render(text:String):Spanned=SpannableStringBuilder(markwon.toMarkdown(text)).apply {
        if(isNotEmpty())setSpan(MarkdownLineHeight(metrics.lineHeight,paragraphGap),0,length,Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
}

@Composable private fun markdownMetrics(style:TextStyle):MarkdownMetrics {
    val density=LocalDensity.current
    val typeface=LocalFontFamilyResolver.current.resolve(style.fontFamily,style.fontWeight?:FontWeight.Normal,
        style.fontStyle?:FontStyle.Normal,style.fontSynthesis?:FontSynthesis.All).value as Typeface
    return MarkdownMetrics(typeface,with(density){style.fontSize.toPx()},with(density){style.lineHeight.toPx().toInt()})
}

@Composable internal fun rememberMarkdownRenderer(style:TextStyle):MarkdownRenderer {
    val context=LocalContext.current
    val density=LocalDensity.current
    val cs=MaterialTheme.colorScheme
    val metrics=markdownMetrics(style)
    val code=markdownMetrics(MaterialTheme.typography.code)
    val headings=MaterialTheme.typography.markdownHeadings.map{markdownMetrics(it)}
    val radius=MaterialTheme.shapes.extraSmall.topStart.toPx(Size(metrics.size*8,metrics.lineHeight.toFloat()),density)
    val foreground=cs.onSurface.toArgb();val link=cs.primary.toArgb();val codeBg=cs.surfaceContainerHigh.toArgb()
    val horizontal=with(density){4.dp.toPx()};val vertical=with(density){2.dp.toPx()}
    val paragraphGap=with(density){8.dp.toPx().toInt()};val indent=with(density){16.dp.toPx().toInt()}
    return remember(context,metrics,code,headings,foreground,link,codeBg,radius,horizontal,vertical,paragraphGap,indent) {
        val markwon=Markwon.builder(context).usePlugin(StrikethroughPlugin.create()).usePlugin(TaskListPlugin.create(context))
            .usePlugin(object:AbstractMarkwonPlugin() {
                override fun configureTheme(builder:MarkwonTheme.Builder) {
                    builder.linkColor(link).codeBackgroundColor(codeBg).codeTextColor(foreground).blockQuoteColor(link)
                        .headingBreakHeight(0).blockMargin(indent)
                }
                override fun configureSpansFactory(builder:MarkwonSpansFactory.Builder) {
                    builder.setFactory(Code::class.java){_,_->RoundedInlineCodeSpan(code,foreground,codeBg,horizontal,vertical,radius,metrics.size)}
                    builder.setFactory(Heading::class.java){_,props->MarkdownHeadingSpan(headings[(CoreProps.HEADING_LEVEL.require(props)-1).coerceIn(0,5)])}
                }
                override fun configureConfiguration(builder:MarkwonConfiguration.Builder) {
                    builder.linkResolver{view,url->val uri=Uri.parse(url);if(uri.scheme in listOf("https","http","mailto"))runCatching{view.context.startActivity(Intent(Intent.ACTION_VIEW,uri))};Unit}
                }
            }).build()
        MarkdownRenderer(markwon,metrics,foreground,link,paragraphGap)
    }
}

private fun setLineHeight(fm:Paint.FontMetricsInt,height:Int) {
    val center=(fm.ascent+fm.descent)/2f
    fm.ascent=floor(center-height/2f).toInt();fm.descent=fm.ascent+height;fm.top=fm.ascent;fm.bottom=fm.descent
}
private class MarkdownLineHeight(private val height:Int,private val paragraphGap:Int):LineHeightSpan {
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt) {
        if(text.subSequence(start,end).isBlank())setLineHeight(fm,paragraphGap)
        else setLineHeight(fm,max(height,fm.descent-fm.ascent))
    }
}
private class MarkdownHeadingSpan(private val metrics:MarkdownMetrics):MetricAffectingSpan(),LineHeightSpan {
    override fun updateMeasureState(paint:TextPaint){paint.typeface=metrics.typeface;paint.textSize=metrics.size}
    override fun updateDrawState(paint:TextPaint)=updateMeasureState(paint)
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){setLineHeight(fm,max(metrics.lineHeight,fm.descent-fm.ascent))}
}

internal class RoundedInlineCodeSpan(private val metrics:MarkdownMetrics,private val foreground:Int,private val background:Int,
    private val horizontal:Float,private val vertical:Float,private val radius:Float,private val baseTextSize:Float):ReplacementSpan() {
    private fun codePaint(original:Paint)=Paint(original).apply{typeface=metrics.typeface;textSize=metrics.size*original.textSize/baseTextSize;color=foreground}
    override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int =
        ceil(codePaint(paint).measureText(text,start,end)+2*horizontal).toInt()
    override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
        val code=codePaint(paint);val font=code.fontMetrics
        val center=y+(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f
        val height=font.descent-font.ascent+2*vertical
        val width=getSize(paint,text,start,end,null).toFloat()
        canvas.drawRoundRect(RectF(x,center-height/2,x+width,center+height/2),radius,radius,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=background})
        canvas.drawText(text,start,end,x+horizontal,center-(font.ascent+font.descent)/2f,code)
    }
}
