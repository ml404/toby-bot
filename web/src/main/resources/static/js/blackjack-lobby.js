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
        fetch("/blackjack/" + guildId + "/create", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ante: ante })
        }).then(function (r) { return r.json().then(function (b) { return { r: r, b: b }; }); })
          .then(function (rb) {
              if (!rb.r.ok || !rb.b.ok) {
                  window.toasts && window.toasts.error(rb.b.error || "Create failed.");
                  return;
              }
              window.location.href = "/blackjack/" + guildId + "/" + rb.b.tableId;
          });
    });
})();
