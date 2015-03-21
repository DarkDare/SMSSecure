package org.SecuredText.SecuredText;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.SecuredText.SecuredText.crypto.IdentityKeyParcelable;
import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.IdentityDatabase;
import org.SecuredText.SecuredText.database.MmsAddressDatabase;
import org.SecuredText.SecuredText.database.MmsDatabase;
import org.SecuredText.SecuredText.database.MmsSmsDatabase;
import org.SecuredText.SecuredText.database.PushDatabase;
import org.SecuredText.SecuredText.database.SmsDatabase;
import org.SecuredText.SecuredText.database.documents.IdentityKeyMismatch;
import org.SecuredText.SecuredText.database.model.MessageRecord;
import org.SecuredText.SecuredText.jobs.PushDecryptJob;
import org.SecuredText.SecuredText.recipients.Recipient;
import org.SecuredText.SecuredText.recipients.RecipientFactory;
import org.SecuredText.SecuredText.recipients.Recipients;
import org.SecuredText.SecuredText.sms.MessageSender;
import org.SecuredText.SecuredText.util.Base64;
import org.whispersystems.textsecure.api.messages.TextSecureEnvelope;
import org.whispersystems.textsecure.internal.push.PushMessageProtos;

import java.io.IOException;

public class ConfirmIdentityDialog extends AlertDialog {

  private static final String TAG = ConfirmIdentityDialog.class.getSimpleName();

  private OnClickListener callback;

  public ConfirmIdentityDialog(Context context,
                               MasterSecret masterSecret,
                               MessageRecord messageRecord,
                               IdentityKeyMismatch mismatch)
  {
    super(context);
    Recipient       recipient       = RecipientFactory.getRecipientForId(context, mismatch.getRecipientId(), false);
    String          name            = recipient.toShortString();
    String          introduction    = String.format(context.getString(R.string.ConfirmIdentityDialog_the_signature_on_this_key_exchange_is_different), name, name);
    SpannableString spannableString = new SpannableString(introduction + " " +
                                                          context.getString(R.string.ConfirmIdentityDialog_you_may_wish_to_verify_this_contact));

    spannableString.setSpan(new VerifySpan(context, masterSecret, mismatch),
                            introduction.length()+1, spannableString.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    setTitle(name);
    setMessage(spannableString);

    setButton(AlertDialog.BUTTON_POSITIVE, "Accept", new AcceptListener(masterSecret, messageRecord, mismatch));
    setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", new CancelListener());
  }

  @Override
  public void show() {
    super.show();
    ((TextView)this.findViewById(android.R.id.message))
                   .setMovementMethod(LinkMovementMethod.getInstance());
  }

  public void setCallback(OnClickListener callback) {
    this.callback = callback;
  }

  private class AcceptListener implements OnClickListener {

    private final MasterSecret        masterSecret;
    private final MessageRecord       messageRecord;
    private final IdentityKeyMismatch mismatch;

    private AcceptListener(MasterSecret masterSecret, MessageRecord messageRecord, IdentityKeyMismatch mismatch) {
      this.masterSecret  = masterSecret;
      this.messageRecord = messageRecord;
      this.mismatch      = mismatch;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
      new AsyncTask<Void, Void, Void>()
      {
        @Override
        protected Void doInBackground(Void... params) {
          IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(getContext());

          identityDatabase.saveIdentity(masterSecret,
                                        mismatch.getRecipientId(),
                                        mismatch.getIdentityKey());

          processMessageRecord(messageRecord);
          processPendingMessageRecords(messageRecord.getThreadId(), mismatch);

          return null;
        }

        private void processMessageRecord(MessageRecord messageRecord) {
          if (messageRecord.isOutgoing()) processOutgoingMessageRecord(messageRecord);
          else                            processIncomingMessageRecord(messageRecord);
        }

        private void processPendingMessageRecords(long threadId, IdentityKeyMismatch mismatch) {
          MmsSmsDatabase        mmsSmsDatabase = DatabaseFactory.getMmsSmsDatabase(getContext());
          Cursor                cursor         = mmsSmsDatabase.getIdentityConflictMessagesForThread(threadId);
          MmsSmsDatabase.Reader reader         = mmsSmsDatabase.readerFor(cursor, masterSecret);
          MessageRecord         record;

          try {
            while ((record = reader.getNext()) != null) {
              for (IdentityKeyMismatch recordMismatch : record.getIdentityKeyMismatches()) {
                if (mismatch.equals(recordMismatch)) {
                  processMessageRecord(record);
                }
              }
            }
          } finally {
            if (reader != null)
              reader.close();
          }
        }

        private void processOutgoingMessageRecord(MessageRecord messageRecord) {
          SmsDatabase        smsDatabase        = DatabaseFactory.getSmsDatabase(getContext());
          MmsDatabase        mmsDatabase        = DatabaseFactory.getMmsDatabase(getContext());
          MmsAddressDatabase mmsAddressDatabase = DatabaseFactory.getMmsAddressDatabase(getContext());

          if (messageRecord.isMms()) {
            mmsDatabase.removeMismatchedIdentity(messageRecord.getId(),
                                                 mismatch.getRecipientId(),
                                                 mismatch.getIdentityKey());

            Recipients recipients = mmsAddressDatabase.getRecipientsForId(messageRecord.getId());

            if (recipients.isGroupRecipient())
              Log.w(TAG, "TODO: Push servce disabled");
            else
              MessageSender.resend(getContext(), masterSecret, messageRecord);
          } else {
            smsDatabase.removeMismatchedIdentity(messageRecord.getId(),
                                                 mismatch.getRecipientId(),
                                                 mismatch.getIdentityKey());

            MessageSender.resend(getContext(), masterSecret, messageRecord);
          }
        }

        private void processIncomingMessageRecord(MessageRecord messageRecord) {
          try {
            PushDatabase pushDatabase = DatabaseFactory.getPushDatabase(getContext());
            SmsDatabase  smsDatabase  = DatabaseFactory.getSmsDatabase(getContext());

            smsDatabase.removeMismatchedIdentity(messageRecord.getId(),
                                                 mismatch.getRecipientId(),
                                                 mismatch.getIdentityKey());

            TextSecureEnvelope envelope = new TextSecureEnvelope(PushMessageProtos.IncomingPushMessageSignal.Type.PREKEY_BUNDLE_VALUE,
                                                                 messageRecord.getIndividualRecipient().getNumber(),
                                                                 messageRecord.getRecipientDeviceId(), "",
                                                                 messageRecord.getDateSent(),
                                                                 Base64.decode(messageRecord.getBody().getBody()));

            long pushId = pushDatabase.insert(envelope);

            ApplicationContext.getInstance(getContext())
                              .getJobManager()
                              .add(new PushDecryptJob(getContext(), pushId, messageRecord.getId(),
                                                      messageRecord.getIndividualRecipient().getNumber()));
          } catch (IOException e) {
            throw new AssertionError(e);
          }
        }

      }.execute();

      if (callback != null) callback.onClick(null, 0);
    }
  }

  private class CancelListener implements OnClickListener {
    @Override
    public void onClick(DialogInterface dialog, int which) {
      if (callback != null) callback.onClick(null, 0);
    }
  }

  private static class VerifySpan extends ClickableSpan {
    private final Context             context;
    private final MasterSecret        masterSecret;
    private final IdentityKeyMismatch mismatch;

    private VerifySpan(Context context, MasterSecret masterSecret, IdentityKeyMismatch mismatch) {
      this.context      = context;
      this.masterSecret = masterSecret;
      this.mismatch     = mismatch;
    }

    @Override
    public void onClick(View widget) {
      Intent intent = new Intent(context, VerifyIdentityActivity.class);
      intent.putExtra("recipient", mismatch.getRecipientId());
      intent.putExtra("master_secret", masterSecret);
      intent.putExtra("remote_identity", new IdentityKeyParcelable(mismatch.getIdentityKey()));
      context.startActivity(intent);
    }
  }

}
