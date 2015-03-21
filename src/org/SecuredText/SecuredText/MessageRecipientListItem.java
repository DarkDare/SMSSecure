/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.SecuredText.SecuredText;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.MmsDatabase;
import org.SecuredText.SecuredText.database.documents.IdentityKeyMismatch;
import org.SecuredText.SecuredText.database.documents.NetworkFailure;
import org.SecuredText.SecuredText.database.model.MessageRecord;
import org.SecuredText.SecuredText.recipients.Recipient;
import org.SecuredText.SecuredText.sms.MessageSender;
import org.SecuredText.SecuredText.util.RecipientViewUtil;

/**
 * A simple view to show the recipients of a message
 *
 * @author Jake McGinty
 */
public class MessageRecipientListItem extends RelativeLayout
    implements Recipient.RecipientModifiedListener
{
  private final static String TAG = MessageRecipientListItem.class.getSimpleName();

  private Recipient  recipient;
  private TextView   fromView;
  private TextView   errorDescription;
  private Button     conflictButton;
  private Button     resendButton;
  private ImageView  contactPhotoImage;

  private final Handler handler = new Handler();

  public MessageRecipientListItem(Context context) {
    super(context);
  }

  public MessageRecipientListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    this.fromView          = (TextView)  findViewById(R.id.from);
    this.errorDescription  = (TextView)  findViewById(R.id.error_description);
    this.contactPhotoImage = (ImageView) findViewById(R.id.contact_photo_image);
    this.conflictButton    = (Button)    findViewById(R.id.conflict_button);
    this.resendButton      = (Button)    findViewById(R.id.resend_button);
  }

  public void set(final MasterSecret masterSecret,
                  final MessageRecord record,
                  final Recipient recipient,
                  final boolean isPushGroup)
  {
    this.recipient = recipient;

    recipient.addListener(this);
    fromView.setText(RecipientViewUtil.formatFrom(getContext(), recipient));

    RecipientViewUtil.setContactPhoto(getContext(), contactPhotoImage, recipient, false);
    setIssueIndicators(masterSecret, record, isPushGroup);
  }

  private void setIssueIndicators(final MasterSecret masterSecret,
                                  final MessageRecord record,
                                  final boolean isPushGroup)
  {
    final NetworkFailure      networkFailure = getNetworkFailure(record);
    final IdentityKeyMismatch keyMismatch    = networkFailure == null ? getKeyMismatch(record) : null;

    String errorText = "";

    if (keyMismatch != null) {
      resendButton.setVisibility(View.GONE);
      conflictButton.setVisibility(View.VISIBLE);

      errorText = getContext().getString(R.string.MessageDetailsRecipient_new_identity);
      conflictButton.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View v) {
          new ConfirmIdentityDialog(getContext(), masterSecret, record, keyMismatch).show();
        }
      });
    } else if (networkFailure != null || (!isPushGroup && record.isFailed())) {
      resendButton.setVisibility(View.VISIBLE);
      resendButton.setEnabled(true);
      conflictButton.setVisibility(View.GONE);

      errorText = getContext().getString(R.string.MessageDetailsRecipient_failed_to_send);
      resendButton.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View v) {
          resendButton.setEnabled(false);
          new ResendAsyncTask(masterSecret, record, networkFailure).execute();
        }
      });
    } else {
      resendButton.setVisibility(View.GONE);
      conflictButton.setVisibility(View.GONE);
    }

    errorDescription.setText(errorText);
    errorDescription.setVisibility(TextUtils.isEmpty(errorText) ? View.GONE : View.VISIBLE);
  }

  private NetworkFailure getNetworkFailure(final MessageRecord record) {
    if (record.hasNetworkFailures()) {
      for (final NetworkFailure failure : record.getNetworkFailures()) {
        if (failure.getRecipientId() == recipient.getRecipientId()) {
          return failure;
        }
      }
    }
    return null;
  }

  private IdentityKeyMismatch getKeyMismatch(final MessageRecord record) {
    if (record.isIdentityMismatchFailure()) {
      for (final IdentityKeyMismatch mismatch : record.getIdentityKeyMismatches()) {
        if (mismatch.getRecipientId() == recipient.getRecipientId()) {
          return mismatch;
        }
      }
    }
    return null;
  }

  public void unbind() {
    if (this.recipient != null) this.recipient.removeListener(this);
  }

  @Override
  public void onModified(final Recipient recipient) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        fromView.setText(RecipientViewUtil.formatFrom(getContext(), recipient));
        RecipientViewUtil.setContactPhoto(getContext(), contactPhotoImage, recipient, false);
      }
    });
  }

  private class ResendAsyncTask extends AsyncTask<Void,Void,Void> {
    private final MasterSecret   masterSecret;
    private final MessageRecord  record;
    private final NetworkFailure failure;

    public ResendAsyncTask(MasterSecret masterSecret, MessageRecord record, NetworkFailure failure) {
      this.masterSecret = masterSecret;
      this.record       = record;
      this.failure      = failure;
    }

    @Override
    protected Void doInBackground(Void... params) {
      MmsDatabase mmsDatabase = DatabaseFactory.getMmsDatabase(getContext());
      mmsDatabase.removeFailure(record.getId(), failure);

      if (record.getRecipients().isGroupRecipient()) {
         Log.w(TAG, "TODO: Push servce disabled");
      } else {
        MessageSender.resend(getContext(), masterSecret, record);
      }
      return null;
    }
  }

}
