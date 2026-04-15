// Live clock in the navbar
(function clock() {
    const el = document.getElementById('clock');
    if (!el) return;
    function tick() {
        el.textContent = new Date().toLocaleString('en-AU', {
            weekday: 'short',
            day:     '2-digit',
            month:   'short',
            year:    'numeric',
            hour:    '2-digit',
            minute:  '2-digit',
            second:  '2-digit',
            hour12:  false,
            timeZone: 'Australia/Sydney'
        });
    }
    tick();
    setInterval(tick, 1000);
})();
