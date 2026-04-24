package bot.toby.economy

import database.dto.TobyCoinPricePointDto
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYAreaRenderer
import org.jfree.data.time.Second
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import org.springframework.stereotype.Component
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Component
class TobyCoinChartRenderer {

    /**
     * Render [points] as a stock-chart-style PNG and return the bytes. The
     * returned buffer is safe to wrap in a JDA [net.dv8tion.jda.api.utils.FileUpload].
     */
    fun renderPng(
        guildName: String,
        points: List<TobyCoinPricePointDto>,
        width: Int = 900,
        height: Int = 400
    ): ByteArray {
        val series = TimeSeries("TOBY/SC")
        points.forEach { p ->
            series.addOrUpdate(Second(Date.from(p.sampledAt)), p.price)
        }
        val dataset = TimeSeriesCollection(series)

        val title = "TOBY • $guildName"
        val chart: JFreeChart = ChartFactory.createTimeSeriesChart(
            title,
            "Time",
            "Price (credits)",
            dataset,
            false,
            false,
            false
        )

        styleStockChart(chart)

        val bos = ByteArrayOutputStream()
        ChartUtils.writeChartAsPNG(bos, chart, width, height)
        return bos.toByteArray()
    }

    private fun styleStockChart(chart: JFreeChart) {
        val bg = Color(0x2B, 0x2D, 0x31)
        val grid = Color(0x44, 0x47, 0x4C)
        val axis = Color(0xB9, 0xBB, 0xBE)
        val line = Color(0x57, 0xF2, 0x87)

        chart.backgroundPaint = bg
        chart.title.paint = Color.WHITE
        chart.title.font = Font("SansSerif", Font.BOLD, 18)

        val plot: XYPlot = chart.plot as XYPlot
        plot.backgroundPaint = bg
        plot.domainGridlinePaint = grid
        plot.rangeGridlinePaint = grid
        plot.outlinePaint = grid

        (plot.domainAxis as DateAxis).apply {
            labelPaint = axis
            tickLabelPaint = axis
            axisLinePaint = axis
            dateFormatOverride = SimpleDateFormat("MMM d HH:mm", Locale.US)
        }
        (plot.rangeAxis as NumberAxis).apply {
            labelPaint = axis
            tickLabelPaint = axis
            axisLinePaint = axis
            autoRangeIncludesZero = false
        }

        val renderer = XYAreaRenderer(XYAreaRenderer.AREA_AND_SHAPES).apply {
            setSeriesStroke(0, BasicStroke(2.0f))
            setSeriesOutlinePaint(0, line)
            setSeriesPaint(
                0,
                GradientPaint(
                    0f, 0f, Color(0x57, 0xF2, 0x87, 160),
                    0f, 400f, Color(0x57, 0xF2, 0x87, 20)
                )
            )
            setOutline(true)
        }
        plot.renderer = renderer
    }
}
