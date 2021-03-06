/*
 * Copyright 2013-2014 Urs Wolfer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.urswolfer.intellij.plugin.gerrit.ui.action;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.util.Consumer;
import com.urswolfer.intellij.plugin.gerrit.GerritModule;
import com.urswolfer.intellij.plugin.gerrit.GerritSettings;
import com.urswolfer.intellij.plugin.gerrit.SelectedRevisions;
import com.urswolfer.intellij.plugin.gerrit.rest.GerritUtil;
import com.urswolfer.intellij.plugin.gerrit.ui.ReviewDialog;
import com.urswolfer.intellij.plugin.gerrit.util.NotificationBuilder;
import com.urswolfer.intellij.plugin.gerrit.util.NotificationService;

import javax.swing.*;
import java.util.List;
import java.util.Map;

import static com.intellij.icons.AllIcons.Actions.*;

/**
 * @author Urs Wolfer
 */
@SuppressWarnings("ComponentNotRegistered") // needs to be setup with correct parameters (ctor); see corresponding factory
public class ReviewAction extends AbstractLoggedInChangeAction {
    public static final String CODE_REVIEW = "Code-Review";
    public static final String VERIFIED = "Verified";

    private final SelectedRevisions selectedRevisions;
    private final SubmitAction submitAction;
    private final NotificationService notificationService;
    private final GerritSettings gerritSettings;

    private String label;
    private int rating;
    private boolean showDialog;

    public ReviewAction(String label,
                        int rating,
                        Icon icon,
                        boolean showDialog,
                        SelectedRevisions selectedRevisions,
                        GerritUtil gerritUtil,
                        SubmitAction submitAction,
                        NotificationService notificationService,
                        GerritSettings gerritSettings) {
        super((rating > 0 ? "+" : "") + rating + (showDialog ? "..." : ""), "Review Change with " + rating, icon);
        this.label = label;
        this.rating = rating;
        this.showDialog = showDialog;
        this.gerritSettings = gerritSettings;
        this.gerritUtil = gerritUtil;
        this.selectedRevisions = selectedRevisions;
        this.submitAction = submitAction;
        this.notificationService = notificationService;
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabled(gerritSettings.isLoginAndPasswordAvailable());
    }

    @Override
    public void actionPerformed(final AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);

        Optional<ChangeInfo> selectedChange = getSelectedChange(anActionEvent);
        if (!selectedChange.isPresent()) {
            return;
        }
        final ChangeInfo changeDetails = selectedChange.get();
        gerritUtil.getComments(changeDetails.id, selectedRevisions.get(changeDetails), project, false, true,
                new Consumer<Map<String, List<CommentInfo>>>() {
            @Override
            public void consume(Map<String, List<CommentInfo>> draftComments) {
                final ReviewInput reviewInput = new ReviewInput();
                reviewInput.label(label, rating);

                for (Map.Entry<String, List<CommentInfo>> entry : draftComments.entrySet()) {
                    for (CommentInfo commentInfo : entry.getValue()) {
                        addComment(reviewInput, entry.getKey(), commentInfo);
                    }
                }

                boolean submitChange = false;
                if (showDialog) {
                    final ReviewDialog dialog = new ReviewDialog(project);
                    dialog.show();
                    if (!dialog.isOK()) {
                        return;
                    }
                    final String message = dialog.getReviewPanel().getMessage();
                    if (!Strings.isNullOrEmpty(message)) {
                        reviewInput.message = message;
                    }
                    submitChange = dialog.getReviewPanel().getSubmitChange();

                    if (!dialog.getReviewPanel().getDoNotify()) {
                        reviewInput.notify = ReviewInput.NotifyHandling.NONE;
                    }
                }

                final boolean finalSubmitChange = submitChange;
                gerritUtil.postReview(changeDetails.id,
                        selectedRevisions.get(changeDetails),
                        reviewInput,
                        project,
                        new Consumer<Void>() {
                            @Override
                            public void consume(Void result) {
                                NotificationBuilder notification = new NotificationBuilder(
                                        project, "Review posted",
                                        buildSuccessMessage(changeDetails, reviewInput))
                                        .hideBalloon();
                                notificationService.notifyInformation(notification);
                                if (finalSubmitChange) {
                                    submitAction.actionPerformed(anActionEvent);
                                }
                            }
                        }
                );
            }
        });
    }

    private void addComment(ReviewInput reviewInput, String path, CommentInfo comment) {
        List<ReviewInput.CommentInput> commentInputs;
        Map<String, List<ReviewInput.CommentInput>> comments = reviewInput.comments;
        if (comments == null) {
            comments = Maps.newHashMap();
            reviewInput.comments = comments;
        }
        if (comments.containsKey(path)) {
            commentInputs = comments.get(path);
        } else {
            commentInputs = Lists.newArrayList();
            comments.put(path, commentInputs);
        }

        ReviewInput.CommentInput commentInput = new ReviewInput.CommentInput();
        commentInput.id = comment.id;
        commentInput.path = comment.path;
        commentInput.side = comment.side;
        commentInput.line = comment.line;
        commentInput.range = comment.range;
        commentInput.inReplyTo = comment.inReplyTo;
        commentInput.updated = comment.updated;
        commentInput.message = comment.message;

        commentInputs.add(commentInput);
    }

    private String buildSuccessMessage(ChangeInfo changeInfo, ReviewInput reviewInput) {
        StringBuilder stringBuilder = new StringBuilder(
                String.format("Review for change '%s' posted", changeInfo.subject)
        );
        if (!reviewInput.labels.isEmpty()) {
            stringBuilder.append(": ");
            stringBuilder.append(Joiner.on(", ").withKeyValueSeparator(": ").join(reviewInput.labels));
        }
        return stringBuilder.toString();
    }

    public abstract static class Proxy extends AnAction implements DumbAware {
        private final ReviewActionFactory reviewActionFactory;
        private final ReviewAction delegate;

        public Proxy(String label, int rating, Icon icon, boolean showDialog) {
            super((rating > 0 ? "+" : "") + rating + (showDialog ? "..." : ""), "Review Change with " + rating, icon);

            reviewActionFactory = GerritModule.getInstance(ReviewActionFactory.class);
            delegate = reviewActionFactory.get(label, rating, icon, showDialog);
        }

        @Override
        public void update(AnActionEvent e) {
            delegate.update(e);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            delegate.actionPerformed(e);
        }
    }

    public static class ReviewPlusTwoProxy extends Proxy {
        public ReviewPlusTwoProxy() {
            super(CODE_REVIEW, 2, Checked, false);
        }
    }

    public static class ReviewPlusTwoDialogProxy extends Proxy {
        public ReviewPlusTwoDialogProxy() {
            super(CODE_REVIEW, 2, Checked, true);
        }
    }

    public static class ReviewPlusOneProxy extends Proxy {
        public ReviewPlusOneProxy() {
            super(CODE_REVIEW, 1, MoveUp, false);
        }
    }

    public static class ReviewPlusOneDialogProxy extends Proxy {
        public ReviewPlusOneDialogProxy() {
            super(CODE_REVIEW, 1, MoveUp, true);
        }
    }

    public static class ReviewNeutralProxy extends Proxy {
        public ReviewNeutralProxy() {
            super(CODE_REVIEW, 0, Forward, false);
        }
    }

    public static class ReviewNeutralDialogProxy extends Proxy {
        public ReviewNeutralDialogProxy() {
            super(CODE_REVIEW, 0, Forward, true);
        }
    }

    public static class ReviewMinusOneProxy extends Proxy {
        public ReviewMinusOneProxy() {
            super(CODE_REVIEW, -1, MoveDown, false);
        }
    }

    public static class ReviewMinusOneDialogProxy extends Proxy {
        public ReviewMinusOneDialogProxy() {
            super(CODE_REVIEW, -1, MoveDown, true);
        }
    }

    public static class ReviewMinusTwoProxy extends Proxy {
        public ReviewMinusTwoProxy() {
            super(CODE_REVIEW, -2, Cancel, false);
        }
    }

    public static class ReviewMinusTwoDialogProxy extends Proxy {
        public ReviewMinusTwoDialogProxy() {
            super(CODE_REVIEW, -2, Cancel, true);
        }
    }


    public static class VerifyPlusOneProxy extends Proxy {
        public VerifyPlusOneProxy() {
            super(VERIFIED, 1, Checked, false);
        }
    }

    public static class VerifyPlusOneDialogProxy extends Proxy {
        public VerifyPlusOneDialogProxy() {
            super(VERIFIED, 1, Checked, true);
        }
    }

    public static class VerifyNeutralProxy extends Proxy {
        public VerifyNeutralProxy() {
            super(VERIFIED, 0, Forward, false);
        }
    }

    public static class VerifyNeutralDialogProxy extends Proxy {
        public VerifyNeutralDialogProxy() {
            super(VERIFIED, 0, Forward, true);
        }
    }

    public static class VerifyMinusOneProxy extends Proxy {
        public VerifyMinusOneProxy() {
            super(VERIFIED, -1, Cancel, false);
        }
    }

    public static class VerifyMinusOneDialogProxy extends Proxy {
        public VerifyMinusOneDialogProxy() {
            super(VERIFIED, -1, Cancel, true);
        }
    }

}
