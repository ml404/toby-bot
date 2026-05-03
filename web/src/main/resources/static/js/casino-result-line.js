// Factory for the `renderXResult(resultEl, body, flashTargetEl)`
// function each per-game JS used to declare verbatim:
//
//   function renderDiceResult(resultEl, body, flashTargetEl) {
//       if (window.TobyCasinoResult) {
//           window.TobyCasinoResult.render({
//               resultEl: resultEl,
//               body: body,
//               classPrefix: 'dice',
//               winLineHtml: '<strong>' + body.landed + '!</strong> …',
//               loseLineHtml: '<strong>' + body.landed + '.</strong> …',
//           });
//       }
//       if (window.CasinoRender) {
//           window.CasinoRender.flashWinPayout(flashTargetEl, body);
//       }
//   }
//
// `TobyCasinoResultLine.factory({ classPrefix, winLine, loseLine })`
// returns the same closure given just the two HTML templates. Each
// template is a `(body) => string` so per-game JS can interpolate
// landed/predicted/net/etc. as needed.

(function (root) {
    'use strict';

    function factory(cfg) {
        const classPrefix = cfg.classPrefix;
        const winLine = cfg.winLine;
        const loseLine = cfg.loseLine;
        return function (resultEl, body, flashTargetEl) {
            if (root && root.TobyCasinoResult) {
                root.TobyCasinoResult.render({
                    resultEl: resultEl,
                    body: body,
                    classPrefix: classPrefix,
                    winLineHtml: winLine(body),
                    loseLineHtml: loseLine(body),
                });
            }
            if (root && root.CasinoRender) {
                root.CasinoRender.flashWinPayout(flashTargetEl, body);
            }
        };
    }

    const api = { factory: factory };
    if (root) root.TobyCasinoResultLine = api;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
})(typeof window !== 'undefined' ? window : null);
