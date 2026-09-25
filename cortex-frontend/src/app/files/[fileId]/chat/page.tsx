import { redirect } from "next/navigation";

/**
 * Kept so existing links still work. Chat is one surface now: the file rides
 * along as a query param and is pre-attached to the first question.
 */
export default async function FileChatPage({
  params,
}: {
  params: Promise<{ fileId: string }>;
}) {
  const { fileId } = await params;
  redirect(`/chat?attach=${fileId}`);
}
