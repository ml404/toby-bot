/*
 * Moderation → Activity tab: per-chart hover tooltip + marker
 * highlight. Server has already rendered every `<circle>` marker with
 * its date / value baked into `data-` attributes, so this is a pure
 * progressive-enhancement layer — without the script the chart still
 * reads as a static area chart, just without the floating tooltip.
 */
(function () {
    'use strict';

    function setup(card) {
        var body = card.querySelector('.activity-chart-body');
        if (!body) return;
        var svg = body.querySelector('.activity-chart');
        var tooltip = body.querySelector('.activity-tooltip');
        if (!svg || !tooltip) return;
        var markers = Array.prototype.slice.call(svg.querySelectorAll('.activity-marker'));
        if (markers.length === 0) return;

        // Pre-read marker geometry / data so we don't touch the DOM on every mousemove.
        var points = markers.map(function (m) {
            return {
                node: m,
                cx: parseFloat(m.getAttribute('cx')),
                cy: parseFloat(m.getAttribute('cy')),
                date: m.getAttribute('data-date'),
                value: m.getAttribute('data-value'),
                unit: m.getAttribute('data-unit') || ''
            };
        });
        var viewBox = svg.viewBox.baseVal;

        function findClosest(svgX) {
            var best = points[0];
            var bestDx = Math.abs(svgX - best.cx);
            for (var i = 1; i < points.length; i++) {
                var dx = Math.abs(svgX - points[i].cx);
                if (dx < bestDx) {
                    best = points[i];
                    bestDx = dx;
                }
            }
            return best;
        }

        function clearActive() {
            for (var i = 0; i < points.length; i++) {
                points[i].node.classList.remove('is-active');
            }
        }

        svg.addEventListener('mousemove', function (event) {
            var rect = svg.getBoundingClientRect();
            if (rect.width === 0) return;
            var svgX = ((event.clientX - rect.left) / rect.width) * viewBox.width;
            var point = findClosest(svgX);

            clearActive();
            point.node.classList.add('is-active');

            tooltip.innerHTML =
                '<span class="activity-tooltip-value">' +
                escapeHtml(point.value) + ' ' + escapeHtml(point.unit) +
                '</span>' +
                '<span class="activity-tooltip-date">' + escapeHtml(point.date) + '</span>';
            tooltip.hidden = false;

            // Tooltip is absolutely positioned inside .activity-chart-body (the
            // nearest positioned ancestor), so the math has to use the body rect,
            // not the card rect.
            var bodyRect = body.getBoundingClientRect();
            tooltip.style.left = (event.clientX - bodyRect.left) + 'px';
            tooltip.style.top = (event.clientY - bodyRect.top) + 'px';
        });

        svg.addEventListener('mouseleave', function () {
            clearActive();
            tooltip.hidden = true;
        });
    }

    function escapeHtml(value) {
        if (value == null) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    document.addEventListener('DOMContentLoaded', function () {
        var cards = document.querySelectorAll('.activity-chart-card');
        for (var i = 0; i < cards.length; i++) setup(cards[i]);
    });
})();
