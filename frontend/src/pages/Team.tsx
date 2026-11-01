import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, UserCog } from "lucide-react";
import { teamApi } from "@/api/endpoints";
import type { Role, TeamMember } from "@/api/types";
import { errorMessage } from "@/api/client";
import { useAuth } from "@/auth/AuthProvider";
import { Avatar, Badge, Button, Card, EmptyState, ErrorBanner, Field, Input, PageHeader, Select, Skeleton } from "@/components/ui/primitives";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/toast";
import { formatDateTime } from "@/lib/format";

const roleOptions: Role[] = ["ADMIN", "MANAGER", "TECHNICIAN"];

export function Team() {
  const { tenant } = useAuth();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<TeamMember | null>(null);
  const [form, setForm] = useState({ fullName: "", email: "", password: "", role: "TECHNICIAN" as Role });
  const [editForm, setEditForm] = useState({
    fullName: "",
    role: "TECHNICIAN" as Role,
    active: true,
    hourlyCost: "0.00",
  });
  const [error, setError] = useState<string | null>(null);

  const team = useQuery({ queryKey: ["team"], queryFn: teamApi.list });

  useEffect(() => {
    if (!editing) return;
    setError(null);
    setEditForm({
      fullName: editing.fullName,
      role: editing.role === "OWNER" ? "ADMIN" : editing.role,
      active: editing.active,
      hourlyCost: (editing.hourlyCost ?? 0).toFixed(2),
    });
  }, [editing]);

  const create = useMutation({
    mutationFn: () => teamApi.create({
      fullName: form.fullName.trim(),
      email: form.email.trim(),
      password: form.password,
      role: form.role,
    }),
    onSuccess: async () => {
      toast.push({ title: "Team member added", description: "Share their sign-in details securely." });
      await queryClient.invalidateQueries({ queryKey: ["team"] });
      setCreateOpen(false);
      setForm({ fullName: "", email: "", password: "", role: "TECHNICIAN" });
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  const update = useMutation({
    mutationFn: () => teamApi.update(editing!.id, {
      fullName: editForm.fullName.trim(),
      role: editing!.role === "OWNER" ? undefined : editForm.role,
      active: editForm.active,
      hourlyCost: Number(editForm.hourlyCost) || 0,
    }),
    onSuccess: async () => {
      toast.push({ title: "Team member updated" });
      await queryClient.invalidateQueries({ queryKey: ["team"] });
      setEditing(null);
    },
    onError: (caught) => setError(errorMessage(caught)),
  });

  return (
    <div className="animate-fade-up">
      <PageHeader
        kicker="Business"
        title="Team"
        description="Owners and admins manage access; technicians see only their assigned work."
        actions={
          <Button
            variant="secondary"
            icon={<Plus className="h-4 w-4" />}
            onClick={() => {
              setError(null);
              setCreateOpen(true);
            }}
          >
            Add member
          </Button>
        }
      />

      <Card className="overflow-hidden">
        {team.isError ? (
          <div className="p-5"><ErrorBanner message={errorMessage(team.error)} /></div>
        ) : team.isLoading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} className="h-14 w-full" />)}
          </div>
        ) : (team.data?.length ?? 0) === 0 ? (
          <EmptyState icon={<UserCog className="h-5 w-5" />} title="No team members yet" description="Add the people who deliver the work, then assign them to jobs." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] border-collapse">
              <thead className="border-b border-sand-200 bg-sand-50/70">
                <tr>
                  <th className="table-head">Member</th>
                  <th className="table-head">Role</th>
                  <th className="table-head">Status</th>
                  <th className="table-head">Last sign-in</th>
                  <th className="table-head text-right">Edit</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-sand-100">
                {team.data?.map((member) => (
                  <tr key={member.id} className="transition hover:bg-sand-50/60">
                    <td className="table-cell">
                      <div className="flex items-center gap-3">
                        <Avatar name={member.fullName} />
                        <div>
                          <p className="font-medium text-ink-900">{member.fullName}</p>
                          <p className="text-[12px] text-ink-400">{member.email}</p>
                        </div>
                      </div>
                    </td>
                    <td className="table-cell">
                      <Badge tone={member.role === "OWNER" ? "brand" : "neutral"}>
                        {member.role.charAt(0) + member.role.slice(1).toLowerCase()}
                      </Badge>
                    </td>
                    <td className="table-cell">
                      <Badge tone={member.active ? "success" : "danger"}>{member.active ? "Active" : "Deactivated"}</Badge>
                    </td>
                    <td className="table-cell">
                      {member.lastLoginAt ? formatDateTime(member.lastLoginAt, tenant?.timezone) : "Never"}
                    </td>
                    <td className="table-cell text-right">
                      <Button
                        size="sm"
                        variant="ghost"
                        icon={<Pencil className="h-3.5 w-3.5" />}
                        onClick={() => {
                          setError(null);
                          setEditing(member);
                        }}
                      >
                        Edit
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Add a team member"
        description="They can sign in immediately with the password you set. Ask them to keep it private."
        footer={
          <>
            <Button variant="outline" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button type="submit" form="create-member" variant="secondary" loading={create.isPending}>
              Add member
            </Button>
          </>
        }
      >
        <form
          id="create-member"
          className="space-y-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            setError(null);
            create.mutate();
          }}
        >
          {error && <ErrorBanner message={error} />}
          <Field label="Full name" htmlFor="member-name">
            <Input
              id="member-name"
              required
              value={form.fullName}
              onChange={(event) => setForm({ ...form, fullName: event.target.value })}
            />
          </Field>
          <Field label="Email" htmlFor="member-email">
            <Input
              id="member-email"
              type="email"
              required
              value={form.email}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
            />
          </Field>
          <Field label="Temporary password" htmlFor="member-password" hint="12+ characters with a letter and a number">
            <Input
              id="member-password"
              type="password"
              required
              autoComplete="new-password"
              value={form.password}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
            />
          </Field>
          <Field label="Role" htmlFor="member-role">
            <Select
              id="member-role"
              value={form.role}
              onChange={(event) => setForm({ ...form, role: event.target.value as Role })}
            >
              {roleOptions.map((role) => (
                <option key={role} value={role}>{role.charAt(0) + role.slice(1).toLowerCase()}</option>
              ))}
            </Select>
          </Field>
        </form>
      </Modal>

      <Modal
        open={!!editing}
        onClose={() => setEditing(null)}
        title={editing ? `Edit ${editing.fullName}` : "Edit member"}
        description={editing?.role === "OWNER"
          ? "The workspace owner role cannot be changed through the API."
          : "Role changes and deactivation take effect on the next request."}
        footer={
          <>
            <Button variant="outline" type="button" onClick={() => setEditing(null)}>Cancel</Button>
            <Button type="submit" form="edit-member" variant="secondary" loading={update.isPending}>
              Save changes
            </Button>
          </>
        }
      >
        <form
          id="edit-member"
          className="space-y-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            setError(null);
            update.mutate();
          }}
        >
          {error && <ErrorBanner message={error} />}
          <Field label="Full name" htmlFor="edit-name">
            <Input
              id="edit-name"
              value={editForm.fullName}
              onChange={(event) => setEditForm({ ...editForm, fullName: event.target.value })}
            />
          </Field>
          <Field label="Role" htmlFor="edit-role">
            <Select
              id="edit-role"
              value={editForm.role}
              disabled={editing?.role === "OWNER"}
              onChange={(event) => setEditForm({ ...editForm, role: event.target.value as Role })}
            >
              {roleOptions.map((role) => (
                <option key={role} value={role}>{role.charAt(0) + role.slice(1).toLowerCase()}</option>
              ))}
            </Select>
          </Field>
          <Field
            label="Hourly cost"
            htmlFor="edit-cost"
            hint="Used for labour costing. Leave at 0 and jobs this person works on report profit as unavailable rather than assuming free labour."
          >
            <Input
              id="edit-cost"
              inputMode="decimal"
              value={editForm.hourlyCost}
              onChange={(event) => setEditForm({ ...editForm, hourlyCost: event.target.value })}
            />
          </Field>
          <label className="flex items-center gap-2.5 text-sm text-ink-700">
            <input
              type="checkbox"
              checked={editForm.active}
              onChange={(event) => setEditForm({ ...editForm, active: event.target.checked })}
              className="h-4 w-4 rounded border-sand-300 text-brand-600 focus:ring-brand-200"
            />
            Active account
          </label>
        </form>
      </Modal>
    </div>
  );
}