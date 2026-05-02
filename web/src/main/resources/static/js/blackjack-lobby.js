(function () {
    "use strict";

    var main = document.getElementById("main");
    var guildId = main.dataset.guildId;

    var createForm = document.getElementById("bj-create");
    var anteInput = document.getElementById("bj-create-ante");
    var balanceEl = document.getElementById("bj-balance");
    var listEl = document.getElementById("bj-table-list");

    function refreshList() {
        return fetch("/blackjack/" + guildId, { credentials: "same-origin", headers: { "Accept": "text/html" } })
            // No public list endpoint; the easiest refresh is reload-on-action.
            .catch(function () {});
    }

    createForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var ante = parseInt(anteInput.value, 10);
        // POSTs go via TobyApi.postJson so the Spring Security CSRF
        // header (read off the <meta name="_csrf"> tag in the head
        // fragment) is included — without it Spring rejects with 403.
        window.TobyApi.postJson("/blackjack/" + guildId + "/create", { ante: ante })
            .then(function (b) {
                if (!b.ok) {
                    window.toasts && window.toasts.error(b.error || "Create failed.");
                    return;
                }
                window.location.href = "/blackjack/" + guildId + "/" + b.tableId;
            });
    });
})();
