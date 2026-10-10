#!/bin/bash

# Replaces electron-builder's default deb postinst, which decides the sandbox mode with an
# unsound probe. See issue 102 in the original f-tree project — everything except
# the chrome-sandbox block is that template, kept verbatim so an upgrade is easy to diff.

if type update-alternatives 2>/dev/null >&1; then
    # Remove previous link if it doesn't use update-alternatives
    if [ -L '/usr/bin/kutumb-desktop' -a -e '/usr/bin/kutumb-desktop' -a "`readlink '/usr/bin/kutumb-desktop'`" != '/etc/alternatives/kutumb-desktop' ]; then
        rm -f '/usr/bin/kutumb-desktop'
    fi
    update-alternatives --install '/usr/bin/kutumb-desktop' 'kutumb-desktop' '/opt/Kutumb/kutumb-desktop' 100 || ln -sf '/opt/Kutumb/kutumb-desktop' '/usr/bin/kutumb-desktop'
else
    ln -sf '/opt/Kutumb/kutumb-desktop' '/usr/bin/kutumb-desktop'
fi

# Give Chromium its SUID sandbox helper, always.
#
# The stock template only does this when it believes user namespaces are unavailable, testing
# with `unshare --user true`. That test asks the wrong question. Ubuntu 24.04+ sets
# kernel.apparmor_restrict_unprivileged_userns=1, under which an unconfined process may still
# create a user namespace but is transitioned into the `unprivileged_userns` profile, whose
# first rule is `audit deny capability`. The namespace exists and has no CAP_SYS_ADMIN, so
# Chromium's own probe rejects the namespace sandbox and falls back to this helper -- which
# the stock template has just set to 0755, and the app aborts before it draws anything.
#
# So: no probe. Set the bit unconditionally, exactly as Google Chrome's and Electron's other
# debs do. Where the namespace sandbox does work Chromium prefers it and never runs this
# binary; where it does not, this is the difference between a window and a core dump. Either
# way the renderer stays sandboxed, which is the point -- --no-sandbox is not a fix.
chown root:root '/opt/Kutumb/chrome-sandbox' || true
chmod 4755 '/opt/Kutumb/chrome-sandbox' || true

if hash update-mime-database 2>/dev/null; then
    update-mime-database /usr/share/mime || true
fi

if hash update-desktop-database 2>/dev/null; then
    update-desktop-database /usr/share/applications || true
fi
