/**
 * Copyright (C) 2011 Whisper Systems
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
package org.SecuredText.SecuredText.sms;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import org.SecuredText.SecuredText.ApplicationContext;
import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.EncryptingSmsDatabase;
import org.SecuredText.SecuredText.database.MmsDatabase;
import org.SecuredText.SecuredText.database.NotInDirectoryException;
import org.SecuredText.SecuredText.database.SecuredTextDirectory;
import org.SecuredText.SecuredText.database.ThreadDatabase;
import org.SecuredText.SecuredText.database.model.MessageRecord;
import org.SecuredText.SecuredText.jobs.MmsSendJob;
import org.SecuredText.SecuredText.jobs.PushGroupSendJob;
import org.SecuredText.SecuredText.jobs.PushMediaSendJob;
import org.SecuredText.SecuredText.jobs.PushTextSendJob;
import org.SecuredText.SecuredText.jobs.SmsSendJob;
import org.SecuredText.SecuredText.mms.OutgoingMediaMessage;
import org.SecuredText.SecuredText.push.SecuredTextCommunicationFactory;
import org.SecuredText.SecuredText.recipients.Recipient;
import org.SecuredText.SecuredText.recipients.Recipients;
import org.SecuredText.SecuredText.util.GroupUtil;
import org.SecuredText.SecuredText.util.SecuredTextPreferences;
import org.SecuredText.SecuredText.util.Util;
import org.whispersystems.jobqueue.JobManager;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.push.ContactTokenDetails;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.IOException;

import ws.com.google.android.mms.MmsException;

public class MessageSender {

  private static final String TAG = MessageSender.class.getSimpleName();

  public static long send(final Context context,
                          final MasterSecret masterSecret,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms)
  {
    EncryptingSmsDatabase database    = DatabaseFactory.getEncryptingSmsDatabase(context);
    Recipients            recipients  = message.getRecipients();
    boolean               keyExchange = message.isKeyExchange();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(masterSecret, allocatedThreadId, message, forceSms);

    sendTextMessage(context, recipients, messageId);

    return allocatedThreadId;
  }

  public static long send(final Context context,
                          final MasterSecret masterSecret,
                          final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms)
  {
    try {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
      MmsDatabase    database       = DatabaseFactory.getMmsDatabase(context);

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipients(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipients recipients = message.getRecipients();
      long       messageId  = database.insertMessageOutbox(masterSecret, message, allocatedThreadId, forceSms);

      sendMediaMessage(context, messageId);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static void resend(Context context, MasterSecret masterSecret, MessageRecord messageRecord) {
    try {
      long messageId = messageRecord.getId();

      if (messageRecord.isMms()) {
        Recipients recipients = DatabaseFactory.getMmsAddressDatabase(context).getRecipientsForId(messageId);
        sendMediaMessage(context, messageId);
      } else {
        Recipients recipients  = messageRecord.getRecipients();
        sendTextMessage(context, recipients, messageId);
      }
    } catch (MmsException e) {
      Log.w(TAG, e);
    }
  }

  private static void sendMediaMessage(Context context, long messageId)
      throws MmsException
  {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new MmsSendJob(context, messageId));
  }

  private static void sendTextMessage(Context context, Recipients recipients, long messageId)
  {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new SmsSendJob(context, messageId, recipients.getPrimaryRecipient().getName()));
  }
}
