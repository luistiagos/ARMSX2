package com.armsx2.updates;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Recebe o resultado da sessão do PackageInstaller.
 *
 * Quando o Android decide que a instalação precisa de confirmação do usuário
 * (o caso normal fora de device owner), ele devolve STATUS_PENDING_USER_ACTION
 * com um Intent pronto — é esse Intent que abre a tela "deseja instalar?".
 * Sem este receiver, a atualização baixa e simplesmente não acontece.
 */
public class UpdateInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "UpdateInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.d(TAG, "status=" + status + " message=" + message);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        }
    }
}
