#!/system/bin/sh
# Optional entry point for a prepared head unit's init hook.
base=/data/local/tmp/open-voyah-controls
[ "$(id -u)" = 0 ] && [ -f "$base/enabled" ] && [ -x "$base/supervise.sh" ] || exit 0
for channel in launcher setting vehicle bluetooth window navigation; do
    if [ "$channel" = launcher ]; then
        dir=$base
        set --
    elif [ "$channel" = window ] || [ "$channel" = navigation ]; then
        dir=$base/$channel
        set -- "$channel"
    else
        dir=$base/locale-$channel
        set -- "$channel"
    fi
    [ -f "$dir/enabled" ] || continue
    nohup /system/bin/sh "$base/supervise.sh" "$@" >> "$dir/worker.log" 2>&1 </dev/null &
done
